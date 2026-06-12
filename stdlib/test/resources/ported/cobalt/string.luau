-- ported from cc-tweaked/Cobalt src/test/resources/spec/string_spec.lua (commit: 5df90f0, MIT)
-- framework: describe/it/expect translated to do...end blocks + local assert helpers
-- edits:
--   - expect(x):eq(y) -> eq(x, y); expect{...}:same{...} -> same(table.pack(...), {...})
--   - expect.error(...):eq/str_match(msg) -> expect_error(...) only — error TEXTS are
--     Cobalt/PUC wording, Luau words them differently ("invalid argument #N to 'f'");
--     upstream text kept in comments
--   - expect.run_coroutine inlined as run_coroutine (resumes echoing yielded values
--     back, returns the body's final results)
--   - version-tagged variants picked against Luau's lstrlib (5.1-lineage %q and number
--     casts despite the 5.3-ish library surface):
--       * %q escapes \0 as \000 -> kept the :lua<=5.1 variant
--       * "very large numbers" -> kept the :lua<=5.2 variant (Luau numbers are doubles;
--         the >=5.3 variant needs exact 64-bit integers)
--       * %d/%x of non-integers truncate without erroring -> kept truncating asserts;
--         NaN/inf -> integer casts are impl-defined, so those value asserts became
--         "does not error" smoke calls
--       * %x of floats kept (:lua<=5.2); %x of 5e19 dropped (out of u64 range —
--         impl-defined value, and no error raised on Luau)
--   - dropped: %q load()-roundtrip tests (load/loadstring unavailable under the
--     sandbox), the whole 'a' option section (Luau string.format has no %a),
--     %s of nil/booleans/tables incl. __name and yielding-__tostring tests (Luau %s
--     requires a string/number argument), string.rep separator tests (Luau rep takes
--     no third argument), string.rep overflow-error tests (count is cast through int),
--     string.dump (not present in spec, none to drop), :lua>=5.4 invalid-flag/format
--     sections
--   - yield-inside-gsub test kept per upstream; Luau may forbid yields across the
--     string.gsub C-call boundary — the verifying run decides
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

-- Run a function as a coroutine, echoing each yielded value back into the next
-- resume (mirrors Cobalt's expect.run_coroutine). Returns the body's final results.
local function run_coroutine(f)
	local co = coroutine.create(f)
	local result = { n = 0 }
	while coroutine.status(co) ~= "dead" do
		local packed = table.pack(coroutine.resume(co, table.unpack(result, 1, result.n)))
		if not packed[1] then error(packed[2], 2) end
		result = { n = packed.n - 1, table.unpack(packed, 2, packed.n) }
	end
	return table.unpack(result, 1, result.n)
end

do -- The string library
	do -- strings can be compared
		assert("alo" < "alo1")
		assert("" < "a")
		assert("alo\0alo" < "alo\0b")
		assert("alo\0alo\0\0" > "alo\0alo\0")
		assert("alo" < "alo\0")
		assert("alo\0" > "alo")
		assert("\0" < "\1")
		assert("\0\0" < "\0\1")
		assert("\1\0a\0a" <= "\1\0a\0a")
		assert(not ("\1\0a\0b" <= "\1\0a\0a"))
		assert("\0\0\0" < "\0\0\0\0")
		assert(not("\0\0\0\0" < "\0\0\0"))
		assert("\0\0\0" <= "\0\0\0\0")
		assert(not("\0\0\0\0" <= "\0\0\0"))
		assert("\0\0\0" <= "\0\0\0")
		assert("\0\0\0" >= "\0\0\0")
		assert(not ("\0\0b" < "\0\0a\0"))

		-- Make sure we're using unsigned comparison.
		assert(string.char(127) >= '\0')
		assert(string.char(128) >= '\0')
		assert(string.char(127) < '\128')
	end

	do -- string.sub
		do -- supports various ranges
			eq(string.sub("123456789",2,4), "234")
			eq(string.sub("123456789",7), "789")
			eq(string.sub("123456789",7,6), "")
			eq(string.sub("123456789",7,7), "7")
			eq(string.sub("123456789",0,0), "")
			eq(string.sub("123456789",-10,10), "123456789")
			eq(string.sub("123456789",1,9), "123456789")
			eq(string.sub("123456789",-10,-20), "")
			eq(string.sub("123456789",-1), "9")
			eq(string.sub("123456789",-4), "6789")
			eq(string.sub("123456789",-6, -4), "456")
			eq(string.sub("\000123456789",3,5), "234")
			eq(("\000123456789"):sub(8), "789")
		end

		do -- on large integers
			eq(string.sub("123456789",-2^31, -4), "123456")
			eq(string.sub("123456789",-2^31, 2^31 - 1), "123456789")
			eq(string.sub("123456789",-2^31, -2^31), "")
		end
	end

	do -- string.find
		do -- works on various ranges
			eq(string.find("123456789", "345"), 3)
			local a,b = string.find("123456789", "345")
			eq(string.sub("123456789", a, b), "345")
			eq(string.find("1234567890123456789", "345", 3), 3)
			eq(string.find("1234567890123456789", "345", 4), 13)
			eq(string.find("1234567890123456789", "346", 4), nil)
			eq(string.find("1234567890123456789", ".45", -9), 13)
			eq(string.find("abcdefg", "\0", 5, 1), nil)
		end

		do -- works on empty strings
			eq(string.find("", ""), 1)
			eq(string.find("", "", 1), 1)
			eq(string.find('', 'aaa', 1), nil)
		end

		do -- accepts an empty match
			same(table.pack(("foo"):find("")), { 1, 0 })
		end

		do -- fails on empty strings when out of bounds (:lua>=5.2 :!cobalt)
			eq(string.find("", "", 2), nil)
			eq(("foo"):find("", 10), nil)
		end

		do -- supports the plain modifier
			eq(('alo(.)alo'):find('(.)', 1, true), 4)
			eq(('alo(.)alo'):find('(.)', 1, 1), 4) -- Coerces to a boolean.
		end

		do -- character classes (:lua>=5.2 — Lua 5.1 doesn't support 'g')
			local classes = "acdglpsuwx"
			local tbl = {
				--[[ 00 ]] "c", "c", "c", "c", "c", "c", "c", "c", "c", "cs", "cs", "cs", "cs", "cs", "c", "c",
				--[[ 10 ]] "c", "c", "c", "c", "c", "c", "c", "c", "c", "c", "c", "c", "c", "c", "c", "c",
				--[[ 20 ]] "s", "gp", "gp", "gp", "gp", "gp", "gp", "gp", "gp", "gp", "gp", "gp", "gp", "gp", "gp", "gp",
				--[[ 30 ]] "dgwx", "dgwx", "dgwx", "dgwx", "dgwx", "dgwx", "dgwx", "dgwx", "dgwx", "dgwx", "gp", "gp", "gp", "gp", "gp", "gp",
				--[[ 40 ]] "gp", "aguwx", "aguwx", "aguwx", "aguwx", "aguwx", "aguwx", "aguw", "aguw", "aguw", "aguw", "aguw", "aguw", "aguw", "aguw", "aguw",
				--[[ 50 ]] "aguw", "aguw", "aguw", "aguw", "aguw", "aguw", "aguw", "aguw", "aguw", "aguw", "aguw", "gp", "gp", "gp", "gp", "gp",
				--[[ 60 ]] "gp", "aglwx", "aglwx", "aglwx", "aglwx", "aglwx", "aglwx", "aglw", "aglw", "aglw", "aglw", "aglw", "aglw", "aglw", "aglw", "aglw",
				--[[ 70 ]] "aglw", "aglw", "aglw", "aglw", "aglw", "aglw", "aglw", "aglw", "aglw", "aglw", "aglw", "gp", "gp", "gp", "gp", "c",
				--[[ 80 ]] "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
				--[[ 90 ]] "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
				--[[ a0 ]] "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
				--[[ b0 ]] "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
				--[[ c0 ]] "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
				--[[ d0 ]] "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
				--[[ e0 ]] "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
				--[[ f0 ]] "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
			}
			for codepoint = 0, 255 do
				local char = string.char(codepoint)

				local matching = ""
				for i = 1, #classes do
					local class = classes:sub(i, i)
					if char:find("%" .. class) then matching = matching .. class end
				end

				eq(matching, tbl[codepoint + 1],
					("Character classes for %d (%q)"):format(codepoint, string.char(codepoint)))
			end
		end
	end

	do -- string.gsub
		do -- yielding within the replacement function (:cobalt)
			-- divergence: Cobalt supports yielding from a gsub replacement; Luau
			-- raises "attempt to yield across metamethod/C-call boundary" (verified)
			local co = coroutine.create(function()
				return ("hello world"):gsub("%w", function(entry)
					local x = coroutine.yield(entry)
					return x:upper()
				end)
			end)
			local ok, err = coroutine.resume(co)
			eq(ok, false)
			assert(string.find(err, "attempt to yield across metamethod/C-call boundary", 1, true),
				"unexpected error: " .. tostring(err))
		end

		do -- back references to position captures do not error (issue #78)
			string.gsub("foo", "()(%1)", "")
		end
	end

	do -- string.len
		do -- via the string library
			eq(string.len(""), 0)
			eq(string.len("\0\0\0"), 3)
			eq(string.len("1234567890"), 10)
		end

		do -- via the operator
			eq(#"", 0)
			eq(#"\0\0\0", 3)
			eq(#"1234567890", 10)
		end
	end

	do -- string.byte
		do -- byte works without a range
			eq(string.byte("a"), 97)
			eq(string.byte("\228"), 228)
		end

		do -- byte works with null bytes
			eq(string.byte("\0"), 0)
			eq(string.byte("\0\0alo\0x", -1), string.byte('x'))
		end

		do -- works with various ranges
			eq(string.byte("ba", 2), 97)
			eq(string.byte("\n\n", 2, -1), 10)
			eq(string.byte("\n\n", 2, 2), 10)
		end

		do -- returns nil on an empty range
			eq(string.byte(""), nil)
			eq(string.byte("hi", -3), nil)
			eq(string.byte("hi", 3), nil)
			eq(string.byte("hi", 9, 10), nil)
			eq(string.byte("hi", 2, 1), nil)
		end
	end

	do -- string.char
		do -- returns an empty string with no args
			eq(string.char(), "")
		end

		do -- accepts multiple characters
			eq(string.char(0, 255, 0), "\0\255\0")
		end

		do -- errors on out-of-range values (:lua>=5.3 :!cobalt)
			-- upstream: str_match("value out of range"); Luau: "invalid value"
			expect_error(string.char, 256)
			expect_error(string.char, -1)
			expect_error(string.char, 2^40)  -- upstream: math.maxinteger or 2^40
			expect_error(string.char, -2^40) -- upstream: math.mininteger or -2^40
		end
	end

	do -- string.byte/string.char round-tripping
		do -- single characters
			eq(string.byte(string.char(255)), 255)
			eq(string.byte(string.char(0)), 0)
		end

		do -- multiple characters
			eq(string.char(0, string.byte("\193"), 0), "\0\193\0")
			eq(string.char(string.byte("\193l\0\243u", 1, -1)), "\193l\0\243u")
			eq(string.char(string.byte("\193l\0\243u", 1, 0)), "")
			eq(string.char(string.byte("\193l\0\243u", -10, 100)), "\193l\0\243u")
		end
	end

	do -- string.upper
		eq(string.upper("ab\0c"), "AB\0C")
	end

	do -- string.lower
		eq(string.lower("\0ABCc%$"), "\0abcc%$")
	end

	do -- string.rep
		do -- with 0 repetitions
			eq(string.rep('teste', 0), '')
		end

		do -- with multiple repetitions
			eq(string.rep('tés\00tę', 2), 'tés\0tętés\000tę')
		end

		do -- with an empty string
			eq(string.rep('', 10), '')
		end

		do -- for various lengths
			for i=0,30 do
				eq(string.len(string.rep('a', i)), i)
			end
		end

		-- dropped: "with a separator :lua>=5.2" — Luau's string.rep takes no separator
		-- argument (str_rep reads exactly two arguments)

		-- dropped: "errors on overflows :lua>=5.3 :!cobalt" — Luau casts the count
		-- through a C int, so 0x4000000000000000 does not reach the overflow check
	end

	do -- string.reverse
		eq(string.reverse "", "")
		eq(string.reverse "\0\1\2\3", "\3\2\1\0")
		eq(string.reverse "\0001234", "4321\0")
	end

	do -- string.format
		do -- 'q' and 's' format strings
			local x = '"\237lo"\n\\'
			eq(string.format('%q%s', x, x), '"\\"\237lo\\"\\\n\\\\""\237lo"\n\\')
		end

		do -- 's' option
			do -- with \0
				eq(string.format("%s\0 is not \0%s", 'not be', 'be'), 'not be\0 is not \0be')
			end

			-- dropped: "converts other values to strings :lua>=5.2", "uses __name
			-- :lua>=5.3" and "supports yielding within __tostring :cobalt" — Luau's %s
			-- requires a string/number argument (no tostring/__tostring coercion)

			-- dropped: "errors on invalid flags :lua>=5.4" — %0.34s/%0.s are accepted
			-- by Luau's scanformat

			do -- supports various modifiers
				local x = string.format('"%-50s"', 'a')
				eq(#x, 52)
				eq(string.sub(x, 1, 4), '"a  ')

				eq(string.format("-%.20s.20s", string.rep("%", 2000)), "-"..string.rep("%", 20)..".20s")
				eq(string.format('"-%20s.20s"', string.rep("%", 2000)), string.format("%q", "-"..string.rep("%", 2000)..".20s"))

				eq(string.format("%.0s", "alo"), "")
				eq(string.format("%.s", "alo"), "")
			end
		end

		do -- 'q' option
			do -- escapes \0 to \000 (:lua<=5.1 — Luau's addquoted is 5.1-style; the
				-- :lua>=5.2 "\0" variant was dropped)
				eq(string.format('%q', "\0"), [["\000"]])
			end

			-- dropped: all %q "roundtrips ..." tests (strings, numbers, booleans, nil,
			-- nan, math.mininteger/maxinteger) — they require load()/loadstring(),
			-- which is unavailable under the sandbox

			do -- errors on non-literal values (:lua>=5.3)
				expect_error(string.format, "%q", {}) -- upstream: "value has no literal form"
			end

			-- dropped: "formats edge-case numbers correctly :lua>=5.4",
			-- "prints hexadecimal floats :lua>=5.4", "errors on invalid flags :lua>=5.4"
		end

		do -- 'c' option
			do -- basic functionality
				eq(string.format("\0%c\0%c%x\0", 225, string.byte("b"), 140), "\0\225\0b8c\0")
				eq(
					string.format("%c",34)..string.format("%c",48)..string.format("%c",90)..string.format("%c",100),
					string.format("%c%c%c%c", 34, 48, 90, 100)
				)
			end

			do -- supports a width
				eq(string.format("%-16c", 97), "a               ")
				eq(string.format("%16c", 97), "               a")
			end

			-- dropped: "errors on invalid flags :lua>=5.4"
		end

		do -- 'd' option
			do -- basic modifiers
				eq(string.format("%%%d %010d", 10, 23), "%10 0000000023")
				eq(string.format("%+08d", 2^31 - 1), "+2147483647")
				eq(string.format("%+08d", -2^31), "-2147483648")
			end

			do -- supports very large numbers
				eq(string.format("%d", -1), "-1")
				eq(tonumber(string.format("%u", 2^62)), 2^62)
				eq(string.format("%d", 2^53), "9007199254740992")
				eq(string.format("%d", -2^53), "-9007199254740992")
			end

			do -- errors on repeated flags (:lua<=5.3)
				expect_error(string.format, "%0000000000d", 10) -- upstream: "invalid format (repeated flags)"
			end

			-- dropped: "errors on invalid options :lua>=5.4" ("%#i")

			do -- non-integer numbers (kept the truncating :lua==5.1-style behavior;
				-- Luau casts through int64 without erroring)
				eq(string.format("%d", 1.2), "1")
				-- NaN/inf -> integer casts are impl-defined (saturating on wasm),
				-- so only assert these do not error:
				string.format("%d", 0/0)
				string.format("%d", math.huge)
			end

			do -- supports various modifiers
				eq(string.format("%#12o", 10), "         012")
				eq(string.format("%013i", -100), "-000000000100")
				eq(string.format("%2.5d", -100), "-00100")
				eq(string.format("%.u", 0), "")
			end
		end

		do -- 'f' option
			do -- round trips
				eq(tonumber(string.format("%f", 10.3)), 10.3)
			end

			do -- with large widths
				local res = string.format('%99.99f', -1e308)
				if #res < 101 + 38 then error("String is " .. #res) end
				eq(tonumber(res), -1e308)
			end

			do -- errors on too large ranges
				-- upstream: str_match("too long"); Luau: "invalid format (width or precision too long)"
				expect_error(string.format, "%1"..("0"):rep(600)..".3d", 10)
				expect_error(string.format, "%10.1"..("0"):rep(600).."004d", 10)
			end

			do -- errors on too large ranges (:lua<5.4 — the :lua>=5.4 variant only
				-- differs in the error message and was dropped)
				expect_error(string.format, "%100.3d", 10) -- upstream: "too long"
				expect_error(string.format, "%1.100d", 10) -- upstream: "too long"
			end

			do -- supports various modifiers
				eq(string.format("%+#014.0f", 100), "+000000000100.")
				eq(string.format("% 1.0E", 100), " 1E+02")
				eq(string.format("%+.3G", 1.5), "+1.5")
				eq(string.format("% .1g", 2^10), " 1e+03")
			end
		end

		do -- 'x' option
			do -- on floats (:lua<=5.2 — Luau truncates instead of erroring)
				eq(string.format("%x", 0.3), "0")
				eq(string.format("%02x", 0.1), "00")
			end

			do -- with large numbers
				eq(string.format("%08X", 2^32 - 1), "FFFFFFFF")

				eq(string.format("%8x", 2^52 - 1), "fffffffffffff")
				eq(string.format("%8x", 0xffffffff), "ffffffff")
				eq(string.format("%8x", 0x7fffffff), "7fffffff")
				eq(string.format("0x%8X", 0x8f000003), "0x8F000003")
			end

			do -- supports formatting flags
				eq(string.format("%#10x", 100), "      0x64")
				eq(string.format("%#-17X", 100), "0X64             ")
			end

			do -- negative numbers (:lua~=5.2)
				eq(string.format("%x", -1), "ffffffffffffffff")
			end

			-- dropped: "with very large numbers" (%x of 5e19, all version variants) —
			-- 5e19 is outside the u64 range, so the value is impl-defined on Luau and
			-- no error is raised
		end

		-- dropped: "'a' option :lua>=5.2" (basic/zero/negative zero/weird numbers) —
		-- Luau's string.format has no %a/%A ("invalid option '%a' to 'format'")

		do -- very large numbers (:lua<=5.2 — fits Luau's doubles; the :lua>=5.3
			-- variant needs exact 64-bit integers and was dropped)
			local x = 2^64 - 2^(64-53)
			eq(x, 0xfffffffffffff800)
			eq(tonumber(string.format("%u", x)), x)
			eq(tonumber(string.format("0X%x", x)), x)
			eq(string.format("%x", x), "fffffffffffff800")
			eq(string.format("%d", x/2), "9223372036854774784")
			eq(string.format("%d", -x/2), "-9223372036854774784")
			eq(string.format("%d", -2^63), "-9223372036854775808")
			eq(string.format("%x", 2^63), "8000000000000000")
		end

		do -- errors on invalid options
			-- upstream (5.4/Cobalt): eq("invalid conversion '%t' to 'format'");
			-- Luau: "invalid option '%t' to 'format'"
			expect_error(string.format, "%t", 10)
		end
	end

	do -- string.pack
		do -- 'z' modifier on exactly the buffer boundary (:lua>=5.3)
			local packed = string.pack("z", ("#"):rep(32))
			eq(packed, "################################\0")
		end
	end
end

return "OK"
