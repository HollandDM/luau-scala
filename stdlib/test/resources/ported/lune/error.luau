-- ported from lune-org/lune tests/globals/error.luau (commit: 1f61836)
-- edits:
--   - added `return "OK"` completion marker at end (zero logic changes)

local errValue = newproxy(false)

local success, result = pcall(function()
	error({
		Inner = errValue,
	})
end)

assert(not success, "Pcall succeeded when erorred")

assert(result ~= nil, "Pcall did not return error")
assert(type(result) == "table", "Pcall error value should have been a table")

assert(result.Inner ~= nil, "Pcall error table should contain inner value")
assert(result.Inner == errValue, "Pcall error table should have correct inner value")

return "OK"
