local order = {}

task.spawn(function()
    table.insert(order, "A-before")
    task.wait(0)
    table.insert(order, "A-after")
end)

task.defer(function()
    table.insert(order, "B")
end)

-- Let scheduler drain
task.wait(0)
task.wait(0)

assert(order[1] == "A-before", tostring(order[1]))
return "wait-zero ok"
