-- ported from cc-tweaked/Cobalt src/test/resources/spec/utf8_spec.lua (commit: 5df90f0, MIT)
-- framework: describe/it/expect translated to do...end blocks + local assert helpers
-- edits:
--   - expect{...}:same{...} -> same(table.pack(...), {...})
--   - error-text matchers relaxed to expect_error — Luau words errors differently;
--     upstream text kept in comments
--   - dropped ":lua>=5.4" out-of-range/out-of-bounds error tests (5.3-lineage utf8
--     returns nil there instead of erroring) and all ":lua>=5.5" sections
--   - dropped "lexing" test: uses load(), not available under the sandbox
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

do -- The utf8 library
	local valid_strings = {
		{ "", {} },
		{ "hello World", { string.byte("hello World", 1, -1) } },
		{ "汉字/漢字", {27721, 23383, 47, 28450, 23383,} },

		-- minimum and maximum values for each sequence size
		{ "\0\x7F\xC2\x80\xDF\xBF\xE0\xA0\x80\xEF\xBF\xBF\xF0\x90\x80\x80\xF4\x8F\xBF\xBF", {0,0x7F, 0x80,0x7FF, 0x800,0xFFFF, 0x10000,0x10FFFF} },
		{ "日本語a-4\0éó", {26085, 26412, 35486, 97, 45, 52, 0, 233, 243} },

		-- Supplementary Characters
		{ "𣲷𠜎𠱓𡁻𠵼ab𠺢", {0x23CB7, 0x2070E, 0x20C53, 0x2107B, 0x20D7C, 0x61, 0x62, 0x20EA2,} },
		{ "𨳊𩶘𦧺𨳒𥄫𤓓\xF4\x8F\xBF\xBF", {0x28CCA, 0x29D98, 0x269FA, 0x28CD2, 0x2512B, 0x244D3, 0x10ffff} },
	}

	local invalid_strings = {
		-- UTF-8 representation for 0x11ffff (value out of valid range)
		"\xF4\x9F\xBF\xBF",

		-- overlong sequences
		"\xC0\x80",          -- zero
		"\xC1\xBF",          -- 0x7F (should be coded in 1 byte)
		"\xE0\x9F\xBF",      -- 0x7FF (should be coded in 2 bytes)
		"\xF0\x8F\xBF\xBF",  -- 0xFFFF (should be coded in 3 bytes)

		-- invalid bytes
		"\x80",  -- continuation byte
		"\xBF",  -- continuation byte
		"\xFE",  -- invalid byte
		"\xFF",  -- invalid byte
	}

	do -- utf8.offset returns nil when out of bounds
		eq(utf8.offset("alo", 5), nil)
		eq(utf8.offset("alo", -4), nil)
	end

	-- dropped: "errors when out of range :lua>=5.4" — 5.3-lineage returns nil/errors differently

	do -- utf8.offset errors on continuation bytes
		expect_error(utf8.offset, "𦧺", 1, 2)  -- upstream: "continuation byte"
		expect_error(utf8.offset, "\x80", 1)   -- upstream: "continuation byte"
	end

	-- dropped: ":lua>=5.5" continuation-byte variant

	do -- utf8.offset returns the character's offset
		eq(utf8.offset("αβγ𦧺", 1), 1)
		eq(utf8.offset("αβγ𦧺", 2), 3)
		eq(utf8.offset("αβγ𦧺", 3), 5)
		eq(utf8.offset("αβγ𦧺", 4), 7)
		eq(utf8.offset("αβγ𦧺", 5), 11)
		eq(utf8.offset("αβγ𦧺", 6), nil)
	end

	-- dropped: "returns the character's end position :lua>=5.5"

	do -- utf8.len works with various strings
		for _, str in pairs(valid_strings) do
			local s, t = str[1], str[2]
			local l = utf8.len(s)
			eq(l, #t, ("len %q"):format(s))
			eq(l, #string.gsub(s, "[\x80-\xBF]", ""), ("len-gsub %q"):format(s))
		end
	end

	do -- utf8.len returns nil and a position on malformed input
		same(table.pack(utf8.len("abc\xE3def")), { nil, 4 })
		same(table.pack(utf8.len("汉字\x80")), { nil, #("汉字") + 1 })
		same(table.pack(utf8.len("\xF4\x9F\xBF")), { nil, 1 })
		same(table.pack(utf8.len("\xF4\x9F\xBF\xBF")), { nil, 1 })
	end

	do -- utf8.len returns nil on invalid character
		for _, str in pairs(invalid_strings) do
			eq(utf8.len(str), nil, ("invalid %q"):format(str))
		end
	end

	do -- utf8.codepoint
		local s = "áéí\128"

		same(table.pack(utf8.codepoint(s, 1, #s - 1)), { 225, 233, 237 })

		for _, str in pairs(valid_strings) do
			local vs, t = str[1], str[2]
			same(table.pack(utf8.codepoint(vs, 1, -1)), t, ("codepoint %q"):format(vs))
		end

		same(table.pack(utf8.codepoint(s, 4, 3)), {}) -- empty bounds

		expect_error(utf8.codepoint, s, 1, #s) -- upstream: "invalid UTF-8 code"
		for _, str in pairs(invalid_strings) do
			expect_error(utf8.codepoint, str)  -- upstream: "invalid UTF-8 code"
		end

		-- dropped: "errors on out of bounds :lua>=5.4"
	end

	do -- utf8.codes errors on invalid codepoints
		local s = "ab\xff"
		expect_error(function() for i in utf8.codes(s) do assert(i) end end)
		-- upstream: str_match(": invalid UTF%-8 code$")
	end

	do -- utf8.char
		for _, str in pairs(valid_strings) do
			local s, t = str[1], str[2]
			eq(utf8.char(table.unpack(t)), s, ("char %q"):format(s))
		end

		eq(utf8.char(), "")
		eq(utf8.char(97, 98, 99), "abc")

		eq(utf8.codepoint(utf8.char(0x10FFFF)), 0x10FFFF) -- roundtrip

		expect_error(utf8.char, 0x7FFFFFFF + 1) -- upstream: "value out of range"
		expect_error(utf8.char, -1)             -- upstream: "value out of range"

		-- dropped: "allows ranges beyond 0x10FFFF :lua>=5.4 :!cobalt"
	end

	do -- utf8.charpattern behaves correctly
		local x = "日本語a-4\0éó"
		local i = 0
		for p, c in string.gmatch(x, "()(" .. utf8.charpattern .. ")") do
			i = i + 1
			eq(utf8.offset(x, i), p)
			eq(utf8.len(x, p), utf8.len(x) - i + 1)
			eq(utf8.len(c), 1)
			for j = 1, #c - 1 do
				eq(utf8.offset(x, 0, p + j - 1), p)
			end
		end
	end

	-- dropped: "lexing" — builds source with \u{...} escapes and load()s it; load is not
	-- available under the sandbox (the \u{...} lexer itself is covered upstream)

	do -- various test strings
		for _, str in pairs(valid_strings) do
			local s, t = str[1], str[2]
			local ctx = ("%q"):format(s)

			eq(utf8.offset(s, 0), 1, ctx)

			local l = #t
			for i = 1, l do
				local pi = utf8.offset(s, i)        -- position of i-th char
				local pi1 = utf8.offset(s, 2, pi)   -- position of next char
				assert(string.find(string.sub(s, pi, pi1 - 1), "^" .. utf8.charpattern .. "$"), ctx)
				eq(utf8.offset(s, -1, pi1), pi, ctx)
				eq(utf8.offset(s, i - l - 1), pi, ctx)
				eq(pi1 - pi, #utf8.char(utf8.codepoint(s, pi)), ctx)
				for j = pi, pi1 - 1 do
					eq(utf8.offset(s, 0, j), pi, ctx)
				end
				for j = pi + 1, pi1 - 1 do
					assert(not utf8.len(s, j), ctx)
				end
				eq(utf8.len(s, pi, pi), 1, ctx)
				eq(utf8.len(s, pi, pi1 - 1), 1, ctx)
				eq(utf8.len(s, pi), l - i + 1, ctx)
				eq(utf8.len(s, pi1), l - i, ctx)
				eq(utf8.len(s, 1, pi), i, ctx)
			end

			local i = 0
			for p, c in utf8.codes(s) do
				i = i + 1
				eq(c, t[i], ctx) eq(p, utf8.offset(s, i), ctx)
				eq(utf8.codepoint(s, p), c, ctx)
			end
			eq(i, #t, ctx)

			i = 0
			for p, c in utf8.codes(s) do
				i = i + 1
				eq(c, t[i], ctx) eq(p, utf8.offset(s, i), ctx)
			end
			eq(i, #t, ctx)

			i = 0
			for c in string.gmatch(s, utf8.charpattern) do
				i = i + 1
				eq(c, utf8.char(t[i]), ctx)
			end
			eq(i, #t, ctx)

			for i = 1, l do
				eq(utf8.offset(s, i), utf8.offset(s, i - l - 1, #s + 1), ctx)
			end
		end
	end
end

return "OK"
