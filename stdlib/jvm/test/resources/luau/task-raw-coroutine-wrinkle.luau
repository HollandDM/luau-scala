-- A coroutine created directly with coroutine.create is NOT a Scheduler Task.
-- Calling task.wait inside it should produce the documented error.
local co = coroutine.create(function()
    local ok, err = pcall(function()
        task.wait(0)
    end)
    assert(not ok, "expected error from task.wait in raw coroutine")
    assert(
        string.find(err, "ADR%-0004") ~= nil or
        string.find(err, "not owned by the Scheduler") ~= nil,
        "error message must reference the known limitation: " .. tostring(err)
    )
    return "wrinkle documented"
end)

local ok, result = coroutine.resume(co)
assert(ok, tostring(result))
assert(result == "wrinkle documented")
return "raw-coroutine ok"
