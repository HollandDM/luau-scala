-- ported from cc-tweaked/Cobalt src/test/resources/spec/base_spec.lua (commit: 5df90f0, MIT)
-- framework: describe/it/expect translated to do...end blocks + local assert helpers
-- edits:
--   - expect(x):eq(y) -> eq(x, y); expect{...}:same{...} -> same(table.pack(...), {...});
--     error-text matchers relaxed to expect_error — Luau words errors differently;
--     upstream text kept in comments (texts confirmed against the engine source kept as eq)
--   - expect.run_coroutine inlined as run_coroutine (copied from vm.luau); tests that
--     consumed run_coroutine's return values restructured to assert inside the body
--   - dropped: "load" section and the two thread getfenv/setfenv tests — load/loadstring
--     and debug.* are unavailable under the sandbox
--   - dropped: "uses __name" and the "pairs" section (__pairs) — Luau lacks both
--   - dropped: ipairs metamethod tests — Luau's ipairs/inext use raw, table-only access
--   - dropped: :cobalt yield-in-__tostring/__index/error-handler tests — Luau forbids
--     yielding across metamethod/C-call boundaries and inside error handlers
--   - dropped: :lua>=5.5 sections; large float/integer formatting tests — Luau's
--     Schubfach shortest-roundtrip printing matches none of the PUC formatting branches
--   - dropped: "can return a non-string value" (:lua<=5.2) — Luau follows the 5.3
--     behavior: __tostring must return a string
--   - "inext returns nil when nothing left": divergence — Luau's inext returns 0 values
--     rather than a single nil; the block asserts Luau's observed behavior
--   - "allows a basic handler": debug.traceback unavailable; uses an identity handler
--   - getfenv/setfenv closure tests included as-is; behavior under the sandbox
--     (safeenv) is empirically verified by the test run
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

do -- The base library
	do -- assert
		do -- returns values by default
			same(table.pack(assert("a", true, 3)), { "a", true, 3 })
		end

		do -- requires at least one argument (:lua~=5.2)
			expect_error(assert) -- upstream: "bad argument #1" ... "value expected"
		end

		do -- defaults to 'assertion failed!'
			eq(expect_error(assert, false), "assertion failed!")
		end

		do -- accepts any type as an error message (:lua>=5.3)
			-- upstream: error value is 123 exactly; Luau's assert coerces the message
			-- through optstring, so only the erroring itself is asserted here
			expect_error(assert, false, 123)
		end
	end

	do -- tonumber
		do -- rejects partial numbers
			local invalid = { "-", " -", "- ", " - ", "0x" }
			for _, k in pairs(invalid) do
				eq(tonumber(k), nil, ("tonumber(%q)"):format(k))
			end
		end
	end

	do -- tostring
		do -- nil
			eq(tostring(nil), "nil")
		end

		do -- booleans
			eq(tostring(true), "true")
			eq(tostring(false), "false")
		end

		do -- numbers: basic conversions
			eq(tostring(12), "12")
			eq("" .. 12, "12")
			eq(12 .. "", "12")

			eq(tostring(1234567890123), "1234567890123")
		end

		do -- numbers: floats
			eq(tostring(-1203), "-1203")
			eq(tostring(1203.125), "1203.125")
			eq(tostring(-0.5), "-0.5")
			eq(tostring(-32767), "-32767")
		end

		-- dropped: "floats roundtrip" :lua>=5.5
		-- dropped: "large floats" :lua<5.5 and :lua>=5.5, "large integers" :lua<=5.2 and
		-- :lua>=5.3 — Luau has no integer subtype and its Schubfach shortest-roundtrip
		-- printer renders e.g. 4611686018427387904.0 as "4611686018427388000", which
		-- matches none of the upstream formatting branches
		-- dropped: "integer-compatible floats are preserved" :lua>=5.3 — needs the 5.3
		-- float/integer distinction ("12.0"); Luau numbers are plain doubles

		do -- integer-compatible floats are truncated (:lua<=5.2)
			eq(tostring(0.0), "0")
			eq("" .. 12, "12")
			eq(12.0 .. "", "12")
			eq(tostring(-1203 + 0.0), "-1203")
		end

		do -- tables
			assert(string.match(tostring({}), "^table:") ~= nil,
				"tostring({}) should match '^table:', got: " .. tostring({}))
		end

		do -- functions
			assert(string.match(tostring(print), "^function:") ~= nil,
				"tostring(print) should match '^function:', got: " .. tostring(print))
		end

		do -- null bytes
			eq(tostring("\0"), "\0")
		end

		-- dropped: "uses __name" :lua>=5.3 — Luau has no __name

		do -- errors if __tostring does not return a string (:lua>=5.3)
			local obj = setmetatable({}, { __tostring = function() return {} end })
			eq(expect_error(tostring, obj), "'__tostring' must return a string")
		end

		-- dropped: "can return a non-string value" :lua<=5.2 — Luau follows the 5.3
		-- behavior and requires __tostring to return a string (see previous block)
		-- dropped: "supports yielding in __tostring" :cobalt — Luau raises "attempt to
		-- yield across metamethod/C-call boundary"
	end

	-- dropped: "pairs" section ("supports yielding in __pairs" :cobalt) — Luau has no
	-- __pairs metamethod (and forbids yielding across metamethods anyway)

	do -- ipairs
		do -- inext returns nil when nothing left (:lua>=5.2)
			local inext = ipairs({})
			-- divergence: Lua 5.2+ returns a single nil here (select('#', ...) == 1);
			-- Luau's inext returns no values at all when iteration is finished
			eq(select('#', inext({}, 0)), 0)
		end

		-- dropped: "uses metamethods" :lua>=5.3 and "uses metamethods on non-table
		-- values" :lua>=5.3 — Luau's ipairs/inext require a table and use raw access
		-- (rawgeti), never consulting __index
		-- dropped: "supports yielding in __index" :cobalt — Luau's ipairs never calls
		-- __index, and yielding across metamethods is forbidden anyway
	end

	do -- rawequal
		do -- two large integers are equal
			-- Older versions of Cobalt uses == instead of .equals, so this was
			-- false.
			eq(rawequal(26463, tonumber("26463")), true)
		end
	end

	-- dropped: "load" section ("returns the error value", "defaults to the global
	-- environment", "accepts custom environments", "propagates the current error
	-- handler") — load/loadstring are not available under the sandbox

	do -- pcall
		do -- supports yielding (:lua>=5.2): with no error
			run_coroutine(function()
				local ok, a, b, c = pcall(function()
					local a, b, c = coroutine.yield(1, 2, 3)
					return a, b, c
				end)

				eq(ok, true)
				same(table.pack(a, b, c), { 1, 2, 3 })
			end)
		end

		do -- supports yielding (:lua>=5.2): with an error
			run_coroutine(function()
				local ok, msg = pcall(function()
					local a, b, c = coroutine.yield(1, 2, 3)
					same(table.pack(a, b, c), { 1, 2, 3 })
					error("Error message", 0)
				end)

				eq(ok, false)
				eq(msg, "Error message")
			end)
		end
	end

	do -- xpcall
		do -- accepts multiple values (:lua>=5.2)
			local ok, res = xpcall(table.pack, function() end, 1, 2, 3)
			eq(ok, true)
			same(res, { 1, 2, 3 })
			eq(res.n, 3)
		end

		local function overflow()
			-- Create a load of useless locals to ensure we fill the stack on PUC Lua faster.
			local a1, b1, c1, d1, e1, f1, g1, h1, i1, j1, k1, l1, m1, n1, o1, p1, q1, r1, s1, t1, u1, v1, w1, x1, y1, z1
			local a2, b2, c2, d2, e2, f2, g2, h2, i2, j2, k2, l2, m2, n2, o2, p2, q2, r2, s2, t2, u2, v2, w2, x2, y2, z2
			local a3, b3, c3, d3, e3, f3, g3, h3, i3, j3, k3, l3, m3, n3, o3, p3, q3, r3, s3, t3, u3, v3, w3, x3, y3, z3
			local a4, b4, c4, d4, e4, f4, g4, h4, i4, j4, k4, l4, m4, n4, o4, p4, q4, r4, s4, t4, u4, v4, w4, x4, y4, z4
			local a5, b5, c5, d5, e5, f5, g5, h5, i5, j5, k5, l5, m5, n5, o5, p5, q5, r5, s5, t5, u5, v5, w5, x5, y5, z5
			local a6, b6, c6, d6, e6, f6, g6, h6, i6, j6, k6, l6, m6, n6, o6, p6, q6, r6, s6, t6, u6, v6, w6, x6, y6, z6
			local a7, b7, c7, d7, e7, f7, g7, h7, i7, j7, k7, l7, m7, n7, o7, p7, q7, r7, s7, t7, u7, v7, w7, x7, y7, z7
			overflow()
		end

		do -- handles stack overflows
			-- PUC Lua and Cobalt handle stacks rather differently. For PUC Lua, the stack holds all Lua values on it.
			-- For Cobalt, the stack just holds the current call frames - each call frame then gets its own array for
			-- storing registers.
			--
			-- This means that the idea of a stack overflow is a little different. That said, the approach used to
			-- handle and recover from stack overflows are similar - we grow the stack to a new limit, and then shrink
			-- it again afterwards.

			do -- allows a basic handler
				-- upstream uses debug.traceback (unavailable here) and asserts
				-- ": stack overflow\nstack traceback:"; an identity handler keeps the point
				local ok, res = xpcall(overflow, function(err) return err end)
				eq(ok, false)
				assert(string.find(res, "stack overflow", 1, true) ~= nil,
					"error should mention 'stack overflow', got: " .. tostring(res))
			end

			do -- the handler can call a small number of nested functions
				local ok, res = xpcall(overflow, function(err)
					local contains_overflow = err:find("stack overflow", 1, true) -- upstream: err:find(": stack overflow$")
					return { err, contains_overflow }
				end)

				eq(ok, false)
				eq(type(res[2]), "number")
			end

			do -- overflowing the stack doesn't allow further overflows
				-- Ensure the stack is properly shrunk
				local ok, res = xpcall(overflow, function(err) return err end)
				eq(ok, false)
				assert(string.find(res, "stack overflow", 1, true) ~= nil,
					"error should mention 'stack overflow', got: " .. tostring(res))

				local ok, res = xpcall(overflow, function(err) return err end)
				eq(ok, false)
				assert(string.find(res, "stack overflow", 1, true) ~= nil,
					"error should mention 'stack overflow', got: " .. tostring(res))
			end

			do -- we can nest error handlers
				-- Ensure we don't prematurely shrink the stack.
				local ok, res = xpcall(overflow, function(err)
					local ok, err = xpcall(function() error("An error occurred", 0) end, function(e) return e end)
					return { ok, err }
				end)
				eq(ok, false)
				same(res, { false, "An error occurred" })
			end
		end

		do -- supports yielding (:lua>=5.2): within the main function
			-- Ensure that yielding within a xpcall works as expected
			run_coroutine(function()
				local ok, a, b, c = xpcall(function()
					return coroutine.yield(1, 2, 3)
				end, function(msg) return msg .. "!" end)

				eq(ok, true)
				eq(a, 1)
				eq(b, 2)
				eq(c, 3)
			end)
		end

		do -- supports yielding (:lua>=5.2): within the main function (with an error)
			run_coroutine(function()
				local ok, msg = xpcall(function()
					local a, b, c = coroutine.yield(1, 2, 3)
					eq(a, 1)
					eq(b, 2)
					eq(c, 3)

					error("Error message", 0)
				end, function(msg) return msg .. "!" end)

				eq(ok, false)
				eq(msg, "Error message!")
			end)
		end

		do -- supports yielding (:lua>=5.2): with an error in the error handler
			run_coroutine(function()
				local ok, msg = xpcall(function()
					local a, b, c = coroutine.yield(1, 2, 3)
					eq(a, 1)
					eq(b, 2)
					eq(c, 3)

					error("Error message")
				end, function(msg) error(msg) end)

				eq(ok, false)
				eq(msg, "error in error handling")
			end)
		end

		-- dropped: "within the error handler" :cobalt and "within the error handler
		-- with an error" :cobalt — Luau cannot yield inside an xpcall error handler
		-- (yield across C-call boundary)
	end

	do -- getfenv/setfenv (:lua==5.1)
		-- dropped: "can set the environment of the current thread" :!cobalt — uses
		-- setfenv(0, ...) on the thread plus loadstring, unavailable under the sandbox
		-- dropped: "can set the environment of another thread" :!cobalt — uses
		-- debug.setfenv/debug.getfenv and loadstring, unavailable under the sandbox

		do -- can set the environment of closures
			local _G = _G
			local g
			local function f() eq(setfenv(2, { a = '10' }), g) end
			g = function()
				local _ = some_global -- Force us to get an _ENV upvalue
				f(); eq(_G.getfenv(1).a, '10')
			end
			g();
			eq(getfenv(g).a, '10')
		end

		do -- more complex closure usage
			local _G = _G

			-- Create a bunch of functions which increment a (global) counter.
			local f = {}
			for i = 1, 10 do f[i] = function(x) A = A + 1; return A, _G.getfenv(x) end end

			A = 10 -- Intentionally non-local
			eq((f[1]()), 11)

			-- Now put all our functions in their own environment
			for i = 1, 10 do eq(setfenv(f[i], { A = i }), f[i]) end

			-- Calling f[3] should just increment its and do nothing else
			eq((f[3]()), 4)
			eq(A, 11)

			-- Passing 1 to get the function's environment
			local a, b = f[8](1)
			eq(b.A, 9)

			-- Passing 0 to get the thread environment.
			a, b = f[8](0)
			eq(b.A, 11)
		end
	end
end

return "OK"
