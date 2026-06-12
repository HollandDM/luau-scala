-- ported from cc-tweaked/Cobalt src/test/resources/spec/vararg_spec.lua (commit: 5df90f0, MIT)
-- framework: describe/it/expect translated to do...end blocks + local assert helpers
-- edits:
--   - expect(x):eq(y) -> eq(x, y); expect{...}:same{...} -> same(table.pack(...), {...})
--   - error-text matchers relaxed to expect_error — Luau words errors differently;
--     upstream text kept in comments
--   - dropped "Lua 5.0 varargs" section (":lua<=5.1 :!cobalt"): Luau has no implicit
--     arg table
--   - dropped "for main chunks" section: uses load(), not available under the sandbox
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

-- Compare a packed result list against an expected array (nil-safe via pack.n).
local function same(actual, expected, ctx)
	local n = math.max(actual.n or #actual, #expected)
	for i = 1, n do
		if actual[i] ~= expected[i] then
			error(("%sindex %d: expected %s, got %s"):format(
				ctx and (tostring(ctx) .. ": ") or "", i,
				tostring(expected[i]), tostring(actual[i])), 2)
		end
	end
end

do -- Varargs
	local unpack = table.unpack
	local function pack(...) return { n = select('#', ...), ... } end

	local function call(f, args) return f(unpack(args, 1, args.n)) end

	-- dropped: "Lua 5.0 varargs" describe block (":lua<=5.1 :!cobalt") — Luau never
	-- creates the implicit `arg` table

	do -- No Lua 5.0 varargs (:lua>=5.2)
		(function(...)
			eq(arg, _G.arg)
			return ...
		end)(1, 2, 3)
	end

	-- dropped: "for main chunks" describe block ("basic packing", "with select('#')")
	-- — uses load(), not available under the sandbox

	do -- select
		do -- selects multiple values
			same(table.pack(select(3, unpack({ 10, 20, 30, 40 }))), { 30, 40 })
		end

		do -- returns nothing when out of bounds
			same(table.pack(select(1)), {})
		end

		do -- accepts negative indexes
			same(table.pack(select(-1, 3, 5, 7)), { 7 })
			same(table.pack(select(-2, 3, 5, 7)), { 5, 7 })
		end

		do -- errors when out of bounds
			expect_error(select, -10000) -- upstream: str_match "index out of range"
		end
	end

	do -- allows unpacking a large number of values (:lua>=5.2)
		-- divergence: Luau bounds string.byte multret by the stack and raises
		-- "stack overflow (string slice too long)" for 2^19 (PUC 5.2+ allows it);
		-- a stack-sized slice works
		local len = 2 ^ 12
		local tbl = { (" "):rep(len):byte(1, -1) }
		eq(#tbl, len)
		expect_error(function() return ((" "):rep(2 ^ 19):byte(1, -1)) end)
		-- upstream (Luau): "stack overflow (string slice too long)"
	end

	do -- supports consing and unconsing
		local function drop(a, ...) return ... end

		local function f(n, a, ...)
			local b
			if n == 0 then
				local b, c, d = ...
				return a, b, c, d, drop(drop(drop(...)))
			else
				n, b, a = n - 1, ..., a
				eq(b, (...))
				return f(n, a, ...)
			end
		end

		local a, b, c, d, e = assert(f(10, 5, 4, 3, 2, 1))
		same({ a, b, c, d, e }, { 5, 4, 3, 2, 1 })

		local a, b, c, d, e = f(4)
		same({ a, b, c, d, e }, { nil, nil, nil, nil, nil })
	end

	do -- calling self functions with varargs
		local t = { 1, 10 }
		function t:f(...) return self[...] + select('#', ...) end
		eq(t:f(1, 4), 3)
		eq(t:f(2), 11)
	end

	do -- calling mixed args/varargs with varargs
		local lim = 20
		local a = {}
		for i = 1, 20 do a[i] = i + 0.3 end

		local function f(a, b, c, d, ...)
			local more = { ... }
			eq(a, 1.3)
			eq(more[1], 5.3)
			eq(more[lim - 4], lim + 0.3)
			eq(more[lim - 3], nil)
		end

		call(f, a)
	end

	do -- calling fixed args with varargs
		local lim = 20
		local a = {}
		for i = 1, 20 do a[i] = i + 0.3 end

		local function g(a, b, c)
			eq(a, 1.3)
			eq(b, 2.3)
			eq(c, 3.3)
		end

		call(g, a)
	end

	do -- calling C function with varargs
		local a = {}
		for i = 1, 20 do a[i] = i end
		eq(call(math.max, a), 20)
	end

	do -- chaining of varargs
		local a = pack(call(next, { _G, nil; n = 2 }))
		local b, c = next(_G)
		same(a, { n = 2, b, c })
		eq(a.n, 2)
	end

	do -- PUC Lua tests
		-- These are dubiously useful, for various reasons:
		--  - Not clear what they're trying to test.
		--  - Describe bugs which only error when Lua was compiled with
		--    assertions

		do -- misc tests
			local function c12(...)
				local x = { ... }; x.n = #x
				local res = (x.n == 2 and x[1] == 1 and x[2] == 2)
				if res then res = 55 end
				return res, 2
			end

			eq(c12(1, 2), 55)
			local a, b = assert(call(c12, { 1, 2 }))
			eq(a, 55) eq(b, 2)

			local a = call(c12, { 1, 2; n = 2 })
			eq(a, 55) eq(b, 2)

			local a = call(c12, { 1, 2; n = 1 })
			eq(a, false)

			eq(c12(1, 2, 3), false)

			local a = pack(call(call, { c12, { 1, 2 } }))
			same(a, { n = 2, 55, 2 })
			eq(a.n, 2)
		end

		do -- no stack overflow with many parameters
			-- From the Lua 5.3 test suite - see https://www.lua.org/bugs.html#5.2.2
			local function f(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10,
				p11, p12, p13, p14, p15, p16, p17, p18, p19, p20,
				p21, p22, p23, p24, p25, p26, p27, p28, p29, p30,
				p31, p32, p33, p34, p35, p36, p37, p38, p39, p40,
				p41, p42, p43, p44, p45, p46, p48, p49, p50, ...)
				local a1, a2, a3, a4, a5, a6, a7
				local a8, a9, a10, a11, a12, a13, a14
			end

			-- assertion fail here
			f()
		end

		do -- missing arguments in tail call
			-- From the Lua 5.4 test suite. I'm assuming there was a bug where
			-- the stack wasn't adjusted, but its not clear to me.
			local function f(a, b, c) return c, b end
			local function g() return f(1, 2) end
			local a, b = g()
			eq(a, nil)
			eq(b, 2)
		end
	end
end

return "OK"
