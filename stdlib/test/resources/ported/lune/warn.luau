-- ported from lune-org/lune tests/globals/warn.luau (commit: 1f61836)
-- edits:
--   - added `return "OK"` completion marker at end

assert(warn ~= nil, "Missing 'warn' global")
assert(
	type(warn) == "function",
	string.format("Global 'warn' should be a function, got '%s'", tostring(type(warn)))
)

return "OK"
