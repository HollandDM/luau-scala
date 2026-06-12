-- ported from cc-tweaked/Cobalt src/test/resources/spec/coroutine_spec.lua (commit: 5df90f0, MIT)
-- framework: describe/it/expect translated to do...end blocks + local assert helpers
-- edits:
--   - return "OK" completion marker at end

local function eq(actual, expected)
	if actual ~= expected then
		error(("expected %s, got %s"):format(tostring(expected), tostring(actual)), 2)
	end
end

do -- Coroutines
	do -- coroutine.running returns the coroutine when not on the main coroutine
		local co = coroutine.create(function() return coroutine.running() end)
		local _, running, is_main = assert(coroutine.resume(co))
		eq(running, co)
		-- divergence: Lua 5.2 returns a second is_main boolean; Luau follows 5.1
		-- and returns only the running thread (verified: second value is nil)
		eq(is_main, nil)
	end

	do -- coroutine.isyieldable returns true inside a coroutine
		local co = coroutine.create(function() return coroutine.isyieldable() end)
		local _, isyieldable = assert(coroutine.resume(co))
		eq(isyieldable, true)
	end
end

return "OK"
