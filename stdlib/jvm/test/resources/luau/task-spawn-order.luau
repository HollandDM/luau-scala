local order = {}

task.spawn(function()
    table.insert(order, "A")
end)

task.defer(function()
    table.insert(order, "B")
end)

task.wait(0)
task.wait(0)

assert(order[1] == "A", "expected A first, got " .. tostring(order[1]))
assert(order[2] == "B", "expected B second, got " .. tostring(order[2]))
return "order ok"
