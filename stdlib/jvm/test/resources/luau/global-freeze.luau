-- Attempting to set a new global should fail with an error
local ok, err = pcall(function()
    _G.newGlobal = 42
end)
assert(not ok, "expected error when mutating frozen _G")
return "freeze ok"
