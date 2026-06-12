-- ported from cc-tweaked/Cobalt src/test/resources/spec/bit32_spec.lua (commit: 5df90f0, MIT)
-- framework: describe/it/expect translated to do...end blocks + local assert helpers
-- edits:
--   - expect(x):eq(y) -> eq(x, y); expect(x):ne(y) -> ne(x, y)
--   - expect.error(...):eq/str_match(msg) -> expect_error(...) only — error TEXTS are
--     Cobalt/PUC wording, Luau words them differently; upstream text kept in comments
--   - return "OK" completion marker at end

local function eq(actual, expected)
	if actual ~= expected then
		error(("expected %s, got %s"):format(tostring(expected), tostring(actual)), 2)
	end
end

local function ne(actual, expected)
	if actual == expected then
		error(("expected something other than %s"):format(tostring(expected)), 2)
	end
end

local function expect_error(f, ...)
	local ok = pcall(f, ...)
	if ok then error("expected an error, got none", 2) end
end

do -- bit32 library
	do -- 0-argument calls
		eq(bit32.band(), bit32.bnot(0))
		eq(bit32.btest(), true)
		eq(bit32.bor(), 0)
		eq(bit32.bxor(), 0)
	end

	do -- band: basic operation
		eq(bit32.band(), bit32.band(0xffffffff))
		eq(bit32.band(1, 2), 0)
	end

	do -- band: out of range numbers
		eq(bit32.band(-1), 0xffffffff)
		eq(bit32.band(2^33 - 1), 0xffffffff)
		eq(bit32.band(-2^33 - 1), 0xffffffff)
		eq(bit32.band(2^33 + 1), 1)
		eq(bit32.band(-2^33 + 1), 1)
		eq(bit32.band(-2^40), 0)
		eq(bit32.band(2^40), 0)
		eq(bit32.band(-2^40 - 2), 0xfffffffe)
		eq(bit32.band(2^40 - 4), 0xfffffffc)
	end

	do -- lrotate: out of range numbers
		eq(bit32.lrotate(0, -1), 0)
		eq(bit32.lrotate(0, 7), 0)
		eq(bit32.lrotate(0x12345678, 4), 0x23456781)
		eq(bit32.rrotate(0x12345678, -4), 0x23456781)
		eq(bit32.lrotate(0x12345678, -8), 0x78123456)
		eq(bit32.rrotate(0x12345678, 8), 0x78123456)
		eq(bit32.lrotate(0xaaaaaaaa, 2), 0xaaaaaaaa)
		eq(bit32.lrotate(0xaaaaaaaa, -2), 0xaaaaaaaa)
	end

	do -- lrotate: out of range shifts
		for i = -50, 50 do
			eq(bit32.lrotate(0x89abcdef, i), bit32.lrotate(0x89abcdef, i % 32))
		end
	end

	do -- shifts: out of range numbers
		eq(bit32.lshift(0x12345678, 4), 0x23456780)
		eq(bit32.lshift(0x12345678, 8), 0x34567800)
		eq(bit32.lshift(0x12345678, -4), 0x01234567)
		eq(bit32.lshift(0x12345678, -8), 0x00123456)
		eq(bit32.lshift(0x12345678, 32), 0)
		eq(bit32.lshift(0x12345678, -32), 0)
		eq(bit32.rshift(0x12345678, 4), 0x01234567)
		eq(bit32.rshift(0x12345678, 8), 0x00123456)
		eq(bit32.rshift(0x12345678, 32), 0)
		eq(bit32.rshift(0x12345678, -32), 0)
		eq(bit32.arshift(0x12345678, 0), 0x12345678)
		eq(bit32.arshift(0x12345678, 1), 0x12345678 / 2)
		eq(bit32.arshift(0x12345678, -1), 0x12345678 * 2)
		eq(bit32.arshift(-1, 1), 0xffffffff)
		eq(bit32.arshift(-1, 24), 0xffffffff)
		eq(bit32.arshift(-1, 32), 0xffffffff)
		eq(bit32.arshift(-1, -1), (-1 * 2) % 2^32)
	end

	do -- some special cases
		-- These are from the bitwise PUC Lua test
		local c = {0, 1, 2, 3, 10, 0x80000000, 0xaaaaaaaa, 0x55555555,
				0xffffffff, 0x7fffffff}

		for _, b in pairs(c) do
			eq(bit32.band(b), b)
			eq(bit32.band(b, b), b)
			eq(bit32.btest(b, b), (b ~= 0))
			eq(bit32.band(b, b, b), b)
			eq(bit32.btest(b, b, b), (b ~= 0))
			eq(bit32.band(b, bit32.bnot(b)), 0)
			eq(bit32.bor(b, bit32.bnot(b)), bit32.bnot(0))
			eq(bit32.bor(b), b)
			eq(bit32.bor(b, b), b)
			eq(bit32.bor(b, b, b), b)
			eq(bit32.bxor(b), b)
			eq(bit32.bxor(b, b), 0)
			eq(bit32.bxor(b, 0), b)
			ne(bit32.bnot(b), b)
			eq(bit32.bnot(bit32.bnot(b)), b)
			eq(bit32.bnot(b), 2^32 - 1 - b)
			eq(bit32.lrotate(b, 32), b)
			eq(bit32.rrotate(b, 32), b)
			eq(bit32.lshift(bit32.lshift(b, -4), 4), bit32.band(b, bit32.bnot(0xf)))
			eq(bit32.rshift(bit32.rshift(b, 4), -4), bit32.band(b, bit32.bnot(0xf)))
			for i = -40, 40 do
				eq(bit32.lshift(b, i), math.floor((b * 2^i) % 2^32))
			end
		end

		assert(not pcall(bit32.band, {}))
		assert(not pcall(bit32.bnot, "a"))
		assert(not pcall(bit32.lshift, 45))
		assert(not pcall(bit32.lshift, 45, print))
		assert(not pcall(bit32.rshift, 45, print))
	end

	do -- extract: extracts bits
		eq(bit32.extract(0x12345678, 0, 4), 8)
		eq(bit32.extract(0x12345678, 4, 4), 7)
		eq(bit32.extract(0xa0001111, 28, 4), 0xa)
		eq(bit32.extract(0xa0001111, 31, 1), 1)
		eq(bit32.extract(0x50000111, 31, 1), 0)
		eq(bit32.extract(0xf2345679, 0, 32), 0xf2345679)
	end

	do -- extract: fails
		expect_error(bit32.extract, 0, -1)     -- upstream: "field cannot be negative"
		expect_error(bit32.extract, 0, 32)     -- upstream: "trying to access non-existent bits"
		expect_error(bit32.extract, 0, 0, 33)  -- upstream: "trying to access non-existent bits"
		expect_error(bit32.extract, 0, 31, 2)  -- upstream: "trying to access non-existent bits"
	end

	do -- replace: replaces bits
		eq(bit32.replace(0x12345678, 5, 28, 4), 0x52345678)
		eq(bit32.replace(0x12345678, 0x87654321, 0, 32), 0x87654321)
		eq(bit32.replace(0, 1, 2), 2^2)
		eq(bit32.replace(0, -1, 4), 2^4)
		eq(bit32.replace(-1, 0, 31), 2^31 - 1)
		eq(bit32.replace(-1, 0, 1, 2), 2^32 - 7)
	end
end

return "OK"
