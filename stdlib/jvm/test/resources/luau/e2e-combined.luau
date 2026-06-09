-- Combines: table, string, math, task, buffer, coroutine
local buf = buffer.create(4)
buffer.writei32(buf, 0, 42)
assert(buffer.readi32(buf, 0) == 42)

local results = {}
local t = task.spawn(function()
    for i = 1, 5 do
        table.insert(results, math.floor(i * 1.5))
    end
end)

task.wait(0)  -- let deferred tasks settle

assert(#results == 5)
assert(results[1] == 1)  -- floor(1.5)
assert(results[5] == 7)  -- floor(7.5)

local s = string.format("count=%d", #results)
assert(s == "count=5")

return "e2e ok"
