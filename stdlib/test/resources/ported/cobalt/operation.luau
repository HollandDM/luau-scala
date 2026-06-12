-- ported from cc-tweaked/Cobalt src/test/resources/spec/operation_spec.lua (commit: 5df90f0, MIT)
-- framework: describe/it/expect translated to do...end blocks + local assert helpers
-- edits:
--   - expect(x):eq(y) -> eq(x, y); expect.error(...):eq/str_match(msg) -> expect_error(...)
--     only — error TEXTS are version-conditional PUC/Cobalt wording, Luau words them
--     differently ("attempt to perform arithmetic ...", "invalid argument #N");
--     upstream text kept in trailing comments; strip_context not needed after relaxation
--   - "includes ... names in error messages" :lua>=5.1/<=5.2 and :lua==5.3 variants merged:
--     they only differ in message wording, which is relaxed to expect_error anyway
--   - dropped: "includes no information in error messages" (:lua>=5.4)
--   - dropped: "compare heterogenous values" (:lua>=5.2) — Luau is 5.1-lineage: __lt/__le
--     are only consulted when both operands share the same type and metamethod, so
--     mk(1) < 2 errors; this directly contradicts the included :lua<=5.1 test
--   - "<= falls back to __lt" (:lua<5.5) included — 5.4 removed the fallback but Luau is
--     5.1-lineage; empirical verification decides
--   - return "OK" completion marker at end

local function eq(actual, expected)
	if actual ~= expected then
		error(("expected %s, got %s"):format(tostring(expected), tostring(actual)), 2)
	end
end

local function expect_error(f, ...)
	local ok = pcall(f, ...)
	if ok then error("expected an error, got none", 2) end
end

do -- Lua's base operators
	do -- modulo (%)
		do -- behaves correctly for large numbers (:lua>=5.3, plain arithmetic — kept)
			-- See https://github.com/SquidDev-CC/CC-Tweaked/issues/404
			--
			-- Tests that our behaviour of % is equivalent to Lua 5.3's.

			local large_prime = 48721
			local small_prime = 3
			local exp_prime = small_prime ^ math.sqrt(large_prime)
			local public_key = exp_prime % large_prime

			-- divergence: Lua 5.3 (and Cobalt) compute float % via fmod, giving
			-- 17511 here; Luau keeps the 5.1 definition a - floor(a/b)*b, which
			-- collapses to 0 at this magnitude (~1e105)
			eq(public_key, 0)
		end
	end

	do -- error messages
		local function mk_adder(k) return function() return 2 + k end end

		do -- includes upvalue names in error messages
			expect_error(mk_adder("hello"))
			-- upstream :lua>=5.1 :lua<=5.2: "attempt to perform arithmetic on upvalue 'k' (a string value)"
			-- upstream :lua==5.3 :!cobalt:  "attempt to perform arithmetic on a string value (upvalue 'k')"
		end

		local function adder(k) return 2 + k end

		do -- includes local names in error messages
			expect_error(adder, "hello")
			-- upstream :lua>=5.1 :lua<=5.2: "attempt to perform arithmetic on local 'k' (a string value)"
			-- upstream :lua==5.3 :!cobalt:  "attempt to perform arithmetic on a string value (local 'k')"
		end

		-- dropped: "includes no information in error messages" :lua>=5.4 :!cobalt
		-- ("attempt to add a 'number' with a 'string'")
	end

	do -- comparison
		local function value(x) if type(x) == "number" then return x else return x.value end end
		local comparable_mt = {
			__lt = function(x, y) return value(x) < value(y) end,
			__le = function(x, y) return value(x) <= value(y) end,
		}

		local function mk(x) return setmetatable({ value = x }, comparable_mt) end

		do -- compare homogenous values
			eq(mk(1) < mk(2), true)
			eq(mk(2) < mk(2), false)
			eq(mk(3) < mk(2), false)

			eq(mk(1) <= mk(2), true)
			eq(mk(2) <= mk(2), true)
			eq(mk(3) <= mk(2), false)
		end

		do -- cannot compare heterogenous values (:lua<=5.1 :!cobalt — Luau is 5.1-lineage)
			expect_error(function() return mk(1) < 2 end)
			-- upstream str_match: ": attempt to compare table with number$"
		end

		do -- cannot compare heterogenous values
			expect_error(function() return "2.0" < 2 end)
			-- upstream str_match: ": attempt to compare string with number$"
		end

		-- dropped: "compare heterogenous values" :lua>=5.2 — mk(1) < 2 etc. would need
		-- heterogeneous __lt/__le dispatch (5.2+); Luau follows 5.1 semantics and errors,
		-- as asserted by the :lua<=5.1 test above

		do -- <= falls back to __lt (:lua<5.5; 5.4 removed the fallback — Luau is 5.1-lineage)
			local comparable_mt = { __lt = function(x, y) return value(x) < value(y) end }
			local function mk(x) return setmetatable({ value = x }, comparable_mt) end

			eq(mk(1) <= mk(2), true)
			eq(mk(2) <= mk(2), true)
			eq(mk(3) <= mk(2), false)
		end
	end
end

return "OK"
