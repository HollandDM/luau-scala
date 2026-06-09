local results = {}

local co = coroutine.create(function(a, b)
    table.insert(results, a)
    local c = coroutine.yield(a + b)
    table.insert(results, c)
    return "done"
end)

local ok, v = coroutine.resume(co, 10, 20)
assert(ok and v == 30, "first resume must yield 30")
ok, v = coroutine.resume(co, 99)
assert(ok and v == "done", "second resume must return done")
assert(coroutine.status(co) == "dead")
assert(results[1] == 10)
assert(results[2] == 99)
return "coroutine ok"
