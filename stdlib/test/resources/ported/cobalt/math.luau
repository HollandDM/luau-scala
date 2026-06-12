-- ported from cc-tweaked/Cobalt src/test/resources/spec/math_spec.lua (commit: 5df90f0, MIT)
-- framework: describe/it/expect translated to do...end blocks + local assert helpers
-- edits:
--   - dropped ":lua>=5.4" exact-PRNG tests: they assert specific values from Lua 5.4's
--     xoshiro256** generator; Luau uses a different PRNG (PCG32), values cannot match
--   - expect.error(...):eq(msg) relaxed to expect_error(...) — Luau words errors differently
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

do -- The math library
	local float_bits = 53

	do -- has 53 floating point bits
		local computed_bits = 24
		local p = 2.0 ^ computed_bits
		while p < p + 1.0 do
			p = p * 2.0
			computed_bits = computed_bits + 1
		end
		eq(computed_bits, float_bits)
	end

	-- dropped: math.random "produces an exact double :lua>=5.4" — Lua 5.4 xoshiro values
	-- dropped: math.random "produces an exact value for integers :lua>=5.4" — same

	do -- math.random fails with three arguments
		expect_error(math.random, 1, 2, 3) -- upstream: "wrong number of arguments"
	end

	do -- two-argument arctangent
		-- divergence: Luau's math.atan ignores a second argument (5.1 behavior,
		-- verified: math.atan(0.5, 0.5) == math.atan(0.5)); the two-argument
		-- spelling is math.atan2(y, x)
		eq(math.atan2(0.5, 0.5), math.pi / 4)
		eq(math.atan(0.5, 0.5), math.atan(0.5))
	end
end

return "OK"
