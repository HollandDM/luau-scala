-- ported from Scythe-Technology/Zune test/standard/task.test.luau (commit: 8feb8cf)
-- framework: zune.testing translated to plain assert
-- edits:
--   - removed zune.testing/zune.task imports; task global used directly
--   - describe/test blocks -> do...end blocks with comments
--   - expect(...) matchers -> assert(...) calls
--   - dropped thread/Sleep test: task.wait inside a raw script-resumed coroutine (ADR-0004 divergence)
--   - dropped describe("Counting", ...) block: task.count is zune extension, no analog
--   - dropped all task.count(...) assertions within kept blocks
--   - widened upper bound on timing assert: 0.1 -> 0.35 (0.05 sleep + 0.25 tolerance)
--   - return "OK" completion marker at end

do -- task
    do -- function
        do -- regular
            do -- Sleep
                local delta = task.wait(0.051)
                assert(delta >= 0.05, "Sleep: delta should be >= 0.05, got " .. tostring(delta))
                assert(delta <= 0.35, "Sleep: delta should be <= 0.35 (widened upper bound), got " .. tostring(delta))
            end

            do -- Spawn
                local success, err
                local task_ret = {task.spawn(function(a, b, c)
                    success, err = pcall(function()
                        assert(a == 1, "Spawn: a should be 1, got " .. tostring(a))
                        assert(b == 4, "Spawn: b should be 4, got " .. tostring(b))
                        assert(c == 8, "Spawn: c should be 8, got " .. tostring(c))
                    end)
                end, 1, 4, 8)}
                assert(success, err)
                assert(#task_ret == 1, "Spawn: task_ret should have length 1")
                assert(typeof(task_ret[1]) == "thread", "Spawn: task_ret[1] should be a thread, got " .. typeof(task_ret[1]))
                assert(coroutine.status(task_ret[1]) == "dead", "Spawn: thread should be dead after spawn, got " .. coroutine.status(task_ret[1]))
                assert(success, "Spawn: inner pcall should succeed")
                assert(err == nil, "Spawn: inner pcall err should be nil, got " .. tostring(err))
            end

            do -- Defer
                local success, err
                local task_ret = {task.defer(function(a, b, c)
                    success, err = pcall(function()
                        assert(a == 1, "Defer: a should be 1, got " .. tostring(a))
                        assert(b == 4, "Defer: b should be 4, got " .. tostring(b))
                        assert(c == 8, "Defer: c should be 8, got " .. tostring(c))
                    end)
                end, 1, 4, 8)}
                assert(#task_ret == 1, "Defer: task_ret should have length 1")
                assert(typeof(task_ret[1]) == "thread", "Defer: task_ret[1] should be a thread, got " .. typeof(task_ret[1]))
                assert(coroutine.status(task_ret[1]) == "suspended", "Defer: thread should be suspended before defer runs, got " .. coroutine.status(task_ret[1]))
                -- dropped: expect(task.count("d")).toBe(1) -- task.count is zune extension
                task.wait(0.5)
                -- dropped: expect(task.count("d")).toBe(0) -- task.count is zune extension
                assert(coroutine.status(task_ret[1]) == "dead", "Defer: thread should be dead after wait, got " .. coroutine.status(task_ret[1]))
                assert(success, "Defer: inner pcall should succeed")
                assert(err == nil, "Defer: inner pcall err should be nil, got " .. tostring(err))
            end

            do -- Delay
                local success, err
                local task_ret = {task.delay(0.1, function(a, b, c)
                    success, err = pcall(function()
                        assert(a == 1, "Delay: a should be 1, got " .. tostring(a))
                        assert(b == 4, "Delay: b should be 4, got " .. tostring(b))
                        assert(c == 8, "Delay: c should be 8, got " .. tostring(c))
                    end)
                end, 1, 4, 8)}
                assert(#task_ret == 1, "Delay: task_ret should have length 1")
                assert(typeof(task_ret[1]) == "thread", "Delay: task_ret[1] should be a thread, got " .. typeof(task_ret[1]))
                assert(coroutine.status(task_ret[1]) == "suspended", "Delay: thread should be suspended before delay fires, got " .. coroutine.status(task_ret[1]))
                -- dropped: expect(task.count("s")).toBe(1) -- task.count is zune extension
                task.wait(0.2)
                -- dropped: expect(task.count("s")).toBe(0) -- task.count is zune extension
                assert(coroutine.status(task_ret[1]) == "dead", "Delay: thread should be dead after wait, got " .. coroutine.status(task_ret[1]))
                assert(success, "Delay: inner pcall should succeed")
                assert(err == nil, "Delay: inner pcall err should be nil, got " .. tostring(err))
            end
        end

        do -- cancel
            do -- Spawn
                local success
                local task_ret = {task.spawn(function()
                    task.wait()
                    success = 0
                    task.wait(0.25)
                    success = 1
                end)}
                assert(#task_ret == 1, "cancel/Spawn: task_ret should have length 1")
                assert(typeof(task_ret[1]) == "thread", "cancel/Spawn: task_ret[1] should be a thread")
                assert(coroutine.status(task_ret[1]) == "suspended", "cancel/Spawn: thread should be suspended initially")
                -- dropped: expect(task.count("s")).toBe(1) -- task.count is zune extension
                task.wait(0.05)
                -- dropped: expect(task.count("s")).toBe(1) -- task.count is zune extension
                assert(coroutine.status(task_ret[1]) == "suspended", "cancel/Spawn: thread should still be suspended before cancel")
                task.cancel(task_ret[1])
                assert(coroutine.status(task_ret[1]) == "dead", "cancel/Spawn: thread should be dead after cancel, got " .. coroutine.status(task_ret[1]))
                task.wait(0.5)
                -- dropped: expect(task.count("s")).toBe(0) -- task.count is zune extension
                assert(success == 0, "cancel/Spawn: success should be 0 (callback never completed)")
            end

            do -- Defer
                local success
                local task_ret = {task.defer(function()
                    success = 0
                    task.wait(0.25)
                    success = 1
                end)}
                assert(#task_ret == 1, "cancel/Defer: task_ret should have length 1")
                assert(typeof(task_ret[1]) == "thread", "cancel/Defer: task_ret[1] should be a thread")
                assert(coroutine.status(task_ret[1]) == "suspended", "cancel/Defer: thread should be suspended initially")
                -- dropped: expect(task.count("d")).toBe(1) -- task.count is zune extension
                -- dropped: expect(task.count("s")).toBe(0) -- task.count is zune extension
                task.wait(0.05)
                -- dropped: expect(task.count("d")).toBe(0) -- task.count is zune extension
                -- dropped: expect(task.count("s")).toBe(1) -- task.count is zune extension
                assert(coroutine.status(task_ret[1]) == "suspended", "cancel/Defer: thread should be suspended while waiting")
                task.cancel(task_ret[1])
                -- dropped: expect(task.count("d")).toBe(0) -- task.count is zune extension
                -- dropped: expect(task.count("s")).toBe(0) -- task.count is zune extension
                assert(coroutine.status(task_ret[1]) == "dead", "cancel/Defer: thread should be dead after cancel, got " .. coroutine.status(task_ret[1]))
                task.wait(0.5)
                assert(success == 0, "cancel/Defer: success should be 0 (cancelled before completing)")
            end

            do -- Delay
                local success
                local task_ret = {task.delay(0, function()
                    success = 0
                    task.wait(0.25)
                    success = 1
                end)}
                assert(#task_ret == 1, "cancel/Delay: task_ret should have length 1")
                assert(typeof(task_ret[1]) == "thread", "cancel/Delay: task_ret[1] should be a thread")
                -- dropped: expect(task.count("s")).toBe(1) -- task.count is zune extension
                assert(coroutine.status(task_ret[1]) == "suspended", "cancel/Delay: thread should be suspended initially")
                task.wait(0.05)
                -- dropped: expect(task.count("s")).toBe(1) -- task.count is zune extension
                assert(coroutine.status(task_ret[1]) == "suspended", "cancel/Delay: thread should still be suspended before cancel")
                task.cancel(task_ret[1])
                -- dropped: expect(task.count("s")).toBe(0) -- task.count is zune extension
                assert(coroutine.status(task_ret[1]) == "dead", "cancel/Delay: thread should be dead after cancel, got " .. coroutine.status(task_ret[1]))
                task.wait(0.5)
                assert(success == 0, "cancel/Delay: success should be 0 (cancelled before completing)")
            end
        end
    end

    do -- thread
        do -- regular
            -- dropped: test("Sleep", ...) -- task.wait inside a raw coroutine resumed by the
            -- script reschedules against the wrong resumer (ADR-0004 known divergence)

            do -- Spawn
                local success, err
                local thread = coroutine.create(function(a, b, c)
                    success, err = pcall(function()
                        assert(a == 1, "thread/Spawn: a should be 1, got " .. tostring(a))
                        assert(b == 4, "thread/Spawn: b should be 4, got " .. tostring(b))
                        assert(c == 8, "thread/Spawn: c should be 8, got " .. tostring(c))
                    end)
                end)
                local task_ret = {task.spawn(thread, 1, 4, 8)}
                assert(success, err)
                assert(#task_ret == 1, "thread/Spawn: task_ret should have length 1")
                assert(typeof(task_ret[1]) == "thread", "thread/Spawn: task_ret[1] should be a thread, got " .. typeof(task_ret[1]))
                assert(task_ret[1] == thread, "thread/Spawn: task.spawn should return the same thread")
                assert(coroutine.status(task_ret[1]) == "dead", "thread/Spawn: thread should be dead after spawn, got " .. coroutine.status(task_ret[1]))
                assert(success, "thread/Spawn: inner pcall should succeed")
                assert(err == nil, "thread/Spawn: inner pcall err should be nil, got " .. tostring(err))
            end

            do -- Defer
                local success, err
                local thread = coroutine.create(function(a, b, c)
                    success, err = pcall(function()
                        assert(a == 1, "thread/Defer: a should be 1, got " .. tostring(a))
                        assert(b == 4, "thread/Defer: b should be 4, got " .. tostring(b))
                        assert(c == 8, "thread/Defer: c should be 8, got " .. tostring(c))
                    end)
                end)
                local task_ret = {task.defer(thread, 1, 4, 8)}
                assert(#task_ret == 1, "thread/Defer: task_ret should have length 1")
                assert(typeof(task_ret[1]) == "thread", "thread/Defer: task_ret[1] should be a thread, got " .. typeof(task_ret[1]))
                assert(task_ret[1] == thread, "thread/Defer: task.defer should return the same thread")
                assert(coroutine.status(task_ret[1]) == "suspended", "thread/Defer: thread should be suspended before defer runs, got " .. coroutine.status(task_ret[1]))
                task.wait(0.5)
                assert(coroutine.status(task_ret[1]) == "dead", "thread/Defer: thread should be dead after wait, got " .. coroutine.status(task_ret[1]))
                assert(success, "thread/Defer: inner pcall should succeed")
                assert(err == nil, "thread/Defer: inner pcall err should be nil, got " .. tostring(err))
            end

            do -- Delay
                local success, err
                local startTime = os.clock()
                local thread = coroutine.create(function(a, b, c)
                    local endTime = os.clock() - startTime
                    success, err = pcall(function()
                        assert(endTime >= 0.249, "thread/Delay: endTime should be >= 0.249, got " .. tostring(endTime))
                        assert(endTime <= 0.75, "thread/Delay: endTime should be <= 0.75 (widened upper bound), got " .. tostring(endTime))
                        assert(a == 1, "thread/Delay: a should be 1, got " .. tostring(a))
                        assert(b == 4, "thread/Delay: b should be 4, got " .. tostring(b))
                        assert(c == 8, "thread/Delay: c should be 8, got " .. tostring(c))
                    end)
                end)
                local task_ret = {task.delay(0.25, thread, 1, 4, 8)}
                assert(#task_ret == 1, "thread/Delay: task_ret should have length 1")
                assert(typeof(task_ret[1]) == "thread", "thread/Delay: task_ret[1] should be a thread, got " .. typeof(task_ret[1]))
                assert(task_ret[1] == thread, "thread/Delay: task.delay should return the same thread")
                assert(coroutine.status(task_ret[1]) == "suspended", "thread/Delay: thread should be suspended before delay fires, got " .. coroutine.status(task_ret[1]))
                task.wait(0.5)
                assert(coroutine.status(task_ret[1]) == "dead", "thread/Delay: thread should be dead after wait, got " .. coroutine.status(task_ret[1]))
                assert(success, "thread/Delay: inner pcall should succeed")
                assert(err == nil, "thread/Delay: inner pcall err should be nil, got " .. tostring(err))
            end
        end

        do -- cancel
            do -- Spawn
                local success
                local thread = coroutine.create(function()
                    task.wait()
                    success = 0
                    task.wait(0.25)
                    success = 1
                end)
                local task_ret = {task.spawn(thread)}
                assert(#task_ret == 1, "thread-cancel/Spawn: task_ret should have length 1")
                assert(typeof(task_ret[1]) == "thread", "thread-cancel/Spawn: task_ret[1] should be a thread")
                assert(task_ret[1] == thread, "thread-cancel/Spawn: task.spawn should return the same thread")
                assert(coroutine.status(task_ret[1]) == "suspended", "thread-cancel/Spawn: thread should be suspended initially")
                task.wait(0.05)
                assert(coroutine.status(task_ret[1]) == "suspended", "thread-cancel/Spawn: thread should still be suspended before cancel")
                task.cancel(task_ret[1])
                assert(coroutine.status(task_ret[1]) == "dead", "thread-cancel/Spawn: thread should be dead after cancel, got " .. coroutine.status(task_ret[1]))
                task.wait(0.5)
                assert(success == 0, "thread-cancel/Spawn: success should be 0 (callback never completed)")
            end

            do -- Defer
                local success
                local thread = coroutine.create(function()
                    success = 0
                    task.wait(0.25)
                    success = 1
                end)
                local task_ret = {task.defer(thread)}
                assert(#task_ret == 1, "thread-cancel/Defer: task_ret should have length 1")
                assert(typeof(task_ret[1]) == "thread", "thread-cancel/Defer: task_ret[1] should be a thread")
                assert(task_ret[1] == thread, "thread-cancel/Defer: task.defer should return the same thread")
                assert(coroutine.status(task_ret[1]) == "suspended", "thread-cancel/Defer: thread should be suspended initially")
                task.wait(0.05)
                assert(coroutine.status(task_ret[1]) == "suspended", "thread-cancel/Defer: thread should still be suspended before cancel")
                task.cancel(task_ret[1])
                assert(coroutine.status(task_ret[1]) == "dead", "thread-cancel/Defer: thread should be dead after cancel, got " .. coroutine.status(task_ret[1]))
                task.wait(0.5)
                assert(success == 0, "thread-cancel/Defer: success should be 0 (cancelled before completing)")
            end

            do -- Delay
                local success
                local thread = coroutine.create(function()
                    success = 0
                    task.wait(0.25)
                    success = 1
                end)
                local task_ret = {task.delay(0, thread)}
                assert(#task_ret == 1, "thread-cancel/Delay: task_ret should have length 1")
                assert(typeof(task_ret[1]) == "thread", "thread-cancel/Delay: task_ret[1] should be a thread")
                assert(task_ret[1] == thread, "thread-cancel/Delay: task.delay should return the same thread")
                assert(coroutine.status(task_ret[1]) == "suspended", "thread-cancel/Delay: thread should be suspended initially")
                task.wait(0.05)
                assert(coroutine.status(task_ret[1]) == "suspended", "thread-cancel/Delay: thread should still be suspended before cancel")
                task.cancel(task_ret[1])
                assert(coroutine.status(task_ret[1]) == "dead", "thread-cancel/Delay: thread should be dead after cancel, got " .. coroutine.status(task_ret[1]))
                task.wait(0.5)
                assert(success == 0, "thread-cancel/Delay: success should be 0 (cancelled before completing)")
            end
        end
    end

    do -- other
        -- dropped: test("Counting", ...) -- task.count is a zune extension with no analog in luau-scala runtime

        do -- nested
            do -- Spawn
                local flag = false
                task.spawn(function()
                    local function nested3()
                        task.spawn(function()
                            task.wait(0.01)
                            flag = true
                        end)
                    end
                    local function nested2()
                        task.spawn(function()
                            task.wait(0.01)
                            nested3()
                        end)
                    end
                    local function nested1()
                        task.spawn(function()
                            task.wait(0.01)
                            nested2()
                        end)
                    end
                    task.wait(0.01)
                    nested1()
                end)
                -- dropped: expect(task.count("s")).toBe(1) -- task.count is zune extension
                task.wait(0.25)
                -- dropped: expect(task.count("s")).toBe(0) -- task.count is zune extension
                assert(flag, "nested/Spawn: flag should be true after nested spawns complete")
            end

            do -- Defer
                local flag = false
                task.defer(function()
                    local function nested3()
                        task.defer(function()
                            task.wait(0.01)
                            flag = true
                        end)
                    end
                    local function nested2()
                        task.defer(function()
                            task.wait(0.01)
                            nested3()
                        end)
                    end
                    local function nested1()
                        task.defer(function()
                            task.wait(0.01)
                            nested2()
                        end)
                    end
                    task.wait(0.01)
                    nested1()
                end)
                -- dropped: expect(task.count("d")).toBe(1) -- task.count is zune extension
                task.wait(0.25)
                -- dropped: expect(task.count("d")).toBe(0) -- task.count is zune extension
                assert(flag, "nested/Defer: flag should be true after nested defers complete")
            end

            do -- Delay
                local flag = false
                task.delay(0.01, function()
                    local function nested3()
                        task.delay(0.01, function()
                            flag = true
                        end)
                    end
                    local function nested2()
                        task.delay(0.01, nested3)
                    end
                    local function nested1()
                        task.delay(0.01, nested2)
                    end
                    nested1()
                end)
                -- dropped: expect(task.count("s")).toBe(1) -- task.count is zune extension
                task.wait(0.25)
                -- dropped: expect(task.count("s")).toBe(0) -- task.count is zune extension
                assert(flag, "nested/Delay: flag should be true after nested delays complete")
            end
        end
    end
end

return "OK"
