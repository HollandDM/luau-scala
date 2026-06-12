-- ported from cc-tweaked/Cobalt src/test/resources/spec/vm_spec.lua (commit: 5df90f0, MIT)
-- framework: describe/it/expect translated to do...end blocks + local assert helpers
-- edits:
--   - expect.run_coroutine inlined as run_coroutine (resumes echoing yielded values back)
--   - "can yield within metamethods" split per metamethod: Luau forbids yielding across
--     VM-invoked metamethods ("attempt to yield across metamethod/C-call boundary");
--     each metamethod block below records Luau's actual behavior
--   - "error positions" test: debug.getinfo unavailable (Debug lib outside Standard set);
--     adapted to assert the error mentions "bad argument" instead of an exact prefix
--   - return "OK" completion marker at end

local function eq(actual, expected, ctx)
	if actual ~= expected then
		error(("%sexpected %s, got %s"):format(
			ctx and (tostring(ctx) .. ": ") or "", tostring(expected), tostring(actual)), 2)
	end
end

local function expect_error(f, ...)
	local ok, err = pcall(f, ...)
	if ok then error("expected an error, got none", 2) end
	return err
end

-- Resume a coroutine to completion, echoing each yield's values back into the
-- next resume (mirrors Cobalt's expect.run_coroutine). Returns ok, err-or-results.
local function run_coroutine_raw(f)
	local co = coroutine.create(f)
	local result = { n = 0 }
	while coroutine.status(co) ~= "dead" do
		local packed = { coroutine.resume(co, table.unpack(result, 1, result.n)) }
		if not packed[1] then return false, packed[2] end
		result = { n = #packed - 1, table.unpack(packed, 2) }
	end
	return true
end

local function run_coroutine(f)
	local ok, err = run_coroutine_raw(f)
	if not ok then error(err, 2) end
end

-- Expect the body to die with Luau's yield-across-metamethod error.
local function yields_forbidden(f, ctx)
	local ok, err = run_coroutine_raw(f)
	if ok then
		error((ctx or "?") .. ": expected yield-across-metamethod error, but it succeeded", 2)
	end
	assert(string.find(err, "attempt to yield across metamethod/C-call boundary", 1, true),
		(ctx or "?") .. ": unexpected error: " .. tostring(err))
end

do -- The Lua VM
	do -- type errors mention the value's type
		local a, b = setmetatable({}, {}), setmetatable({}, {})
		-- upstream asserts Lua 5.3 __name in messages ("attempt to compare type a with
		-- type b"); Luau has no __name — assert the comparisons fail at all
		expect_error(function() return a < b end)
		expect_error(function() return a < a end)
		expect_error(string.match, a)
	end

	do -- yielding within metamethods, probed per metamethod
		local create, ops
		create = function(val) return setmetatable({ x = val }, ops) end
		ops = {
			__add = function(x, y)
				local a, b = coroutine.yield(x, y)
				return create(a.x + b.x)
			end,
			__div = function(x, y)
				local a, b = coroutine.yield(x, y)
				return create(a.x / b.x)
			end,
			__concat = function(x, y)
				local a, b = coroutine.yield(x, y)
				return create(a.x .. b.x)
			end,
			__eq = function(x, y)
				local a, b = coroutine.yield(x, y)
				return a.x == b.x
			end,
			__lt = function(x, y)
				local a, b = coroutine.yield(x, y)
				return a.x < b.x
			end,
			__le = function(x, y)
				local a, b = coroutine.yield(x, y)
				return a.x <= b.x
			end,
			__len = function(x)
				return coroutine.yield(x).x
			end,
			__index = function(tbl, key)
				local res = coroutine.yield(key)
				return res:upper()
			end,
			__newindex = function(tbl, key, val)
				local rKey, rVal = coroutine.yield(key, val)
				rawset(tbl, rKey, rVal .. "!")
			end,
		}

		local varA = create(2)
		local varB = create(3)

		-- divergence probe: Cobalt/Lua 5.2 allow all of these to yield; Luau raises
		-- "attempt to yield across metamethod/C-call boundary" for VM-invoked
		-- metamethods. Each block asserts Luau's observed behavior.
		yields_forbidden(function() return (varA + varB).x end, "__add")
		yields_forbidden(function() return (varA / varB).x end, "__div")
		yields_forbidden(function() return (varA .. varB).x end, "__concat")
		yields_forbidden(function() return varA == varB end, "__eq")
		yields_forbidden(function() return varA < varB end, "__lt")
		yields_forbidden(function() return varA <= varB end, "__le")
		yields_forbidden(function() return #varA end, "__len")
		yields_forbidden(function() return varA.hello end, "__index")
		yields_forbidden(function() varA.hello = "bar" end, "__newindex")

		-- the non-yielding paths still work: same metamethods, no yield inside
		local plain
		local pops = {
			__add = function(x, y) return setmetatable({ x = x.x + y.x }, getmetatable(x)) end,
			__index = function(tbl, key) return key:upper() end,
		}
		plain = setmetatable({ x = 2 }, pops)
		eq((plain + plain).x, 4)
		eq(plain.hello, "HELLO")
	end

	do -- error positions: errors from stdlib calls inside a coroutine carry position info
		local function f() string.gsub(nil) end
		local ok, err = coroutine.resume(coroutine.create(f))
		eq(ok, false)
		-- divergence: Luau words this "invalid argument #1 to 'gsub'" (PUC says "bad
		-- argument"); the upstream point — the chunk:line prefix is present — holds
		assert(string.find(err, "invalid argument", 1, true) ~= nil,
			"error should mention 'invalid argument', got: " .. tostring(err))
		assert(string.find(err, "cobalt/vm.luau:", 1, true) == 1,
			"error should start with the chunk position, got: " .. tostring(err))
	end
end

return "OK"
