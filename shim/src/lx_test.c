/* shim/src/lx_test.c -- C-level Shim ABI tests */
#include "lx.h"
#include "lua.h"
#include <stdio.h>
#include <assert.h>
#include <string.h>

/* ------------------------------------------------------------------ */
/* Globals: test upcall state                                           */
/* ------------------------------------------------------------------ */

static int g_upcall_outcome = LX_RETURN;
static int g_upcall_nresults = 0;
static int g_upcall_called = 0;
static int32_t g_upcall_fnid = -1;

static int test_upcall(lx_State state, lx_Thread thread,
                        int32_t fnId, int nArgs, int* nResults) {
    (void)nArgs;
    g_upcall_called = 1;
    g_upcall_fnid   = fnId;
    *nResults = g_upcall_nresults;
    if (g_upcall_outcome == LX_RETURN) {
        for (int i = 0; i < g_upcall_nresults; i++)
            lx_push_number(state, thread, (double)(i + 42));
    } else if (g_upcall_outcome == LX_FAIL) {
        lx_push_lstring(state, thread, "test error", 10);
    } else if (g_upcall_outcome == LX_SUSPEND) {
        lx_set_suspend_token(state, thread, 0xDEADC0DE);
    }
    return g_upcall_outcome;
}

/* ------------------------------------------------------------------ */
/* Test: compile-and-load, resume, return value                        */
/* ------------------------------------------------------------------ */

static void test_basic_resume(void) {
    lx_State s = lx_newstate(test_upcall);
    assert(s != NULL);
    lx_open_libs(s);
    lx_Thread main = lx_main_thread(s);

    const char* src = "return 1 + 1";
    char errbuf[256] = {0};
    int rc = lx_compile_and_load(s, src, strlen(src), "test", 1, 1, errbuf, sizeof(errbuf));
    assert(rc == 0);

    int nResults = 0;
    int status = lx_resume(s, main, 0, &nResults);
    assert(status == LX_RESUME_OK);
    assert(nResults == 1);

    int ok = 0;
    double v = lx_to_number(s, main, 1, &ok);
    assert(ok && v == 2.0);

    lx_close(s);
    printf("PASS test_basic_resume\n");
}

/* ------------------------------------------------------------------ */
/* Test: runtime error converts to status, does not crash              */
/* ------------------------------------------------------------------ */

static void test_error_becomes_status(void) {
    lx_State s = lx_newstate(test_upcall);
    lx_open_libs(s);
    lx_Thread main = lx_main_thread(s);

    const char* src = "error('boom')";
    char errbuf[256] = {0};
    int rc = lx_compile_and_load(s, src, strlen(src), "err_test", 1, 1, errbuf, sizeof(errbuf));
    assert(rc == 0);

    int nResults = 0;
    int status = lx_resume(s, main, 0, &nResults);
    assert(status == LX_RESUME_ERR);

    char msgbuf[256] = {0};
    lx_copy_error(s, main, msgbuf, sizeof(msgbuf));
    assert(strstr(msgbuf, "boom") != NULL);

    lx_close(s);
    printf("PASS test_error_becomes_status\n");
}

/* ------------------------------------------------------------------ */
/* Test: native function LX_RETURN tri-state                           */
/* ------------------------------------------------------------------ */

static void test_native_return(void) {
    lx_State s = lx_newstate(test_upcall);
    lx_open_libs(s);
    lx_Thread main = lx_main_thread(s);

    g_upcall_outcome  = LX_RETURN;
    g_upcall_nresults = 2;
    g_upcall_called   = 0;

    lx_register_native(s, 7, "myfn");
    lua_setglobal((lua_State*)s, "myfn");

    const char* src = "return myfn()";
    char errbuf[256] = {0};
    lx_compile_and_load(s, src, strlen(src), "native_test", 1, 1, errbuf, sizeof(errbuf));

    int nResults = 0;
    int status = lx_resume(s, main, 0, &nResults);
    assert(status == LX_RESUME_OK);
    assert(g_upcall_called == 1);
    assert(g_upcall_fnid == 7);
    assert(nResults == 2);

    lx_close(s);
    printf("PASS test_native_return\n");
}

/* ------------------------------------------------------------------ */
/* Test: native function LX_FAIL -> Luau error                         */
/* ------------------------------------------------------------------ */

static void test_native_fail(void) {
    lx_State s = lx_newstate(test_upcall);
    lx_open_libs(s);
    lx_Thread main = lx_main_thread(s);

    g_upcall_outcome  = LX_FAIL;
    g_upcall_nresults = 0;
    g_upcall_called   = 0;

    lx_register_native(s, 99, "failfn");
    lua_setglobal((lua_State*)s, "failfn");

    const char* src = "local ok, err = pcall(failfn); return ok, err";
    char errbuf[256] = {0};
    lx_compile_and_load(s, src, strlen(src), "fail_test", 1, 1, errbuf, sizeof(errbuf));

    int nResults = 0;
    int status = lx_resume(s, main, 0, &nResults);
    assert(status == LX_RESUME_OK);
    assert(nResults == 2);

    int bval = lx_to_boolean(s, main, 1);
    assert(bval == 0);

    lx_close(s);
    printf("PASS test_native_fail\n");
}

/* ------------------------------------------------------------------ */
/* Test: native function LX_SUSPEND -> yield -> resume with value      */
/* ------------------------------------------------------------------ */

static void test_native_suspend_resume(void) {
    lx_State s  = lx_newstate(test_upcall);
    lx_open_libs(s);

    lx_Thread main = lx_main_thread(s);

    g_upcall_outcome  = LX_SUSPEND;
    g_upcall_nresults = 0;
    g_upcall_called   = 0;

    lx_register_native(s, 55, "asyncfn");
    lua_setglobal((lua_State*)s, "asyncfn");

    const char* src = "local v = asyncfn(); return v";
    char errbuf[256] = {0};
    lx_compile_and_load(s, src, strlen(src), "suspend_test", 1, 1, errbuf, sizeof(errbuf));

    int nResults = 0;
    int status = lx_resume(s, main, 0, &nResults);
    assert(status == LX_RESUME_YIELD);

    int64_t token = lx_get_suspend_token(s, main);
    assert(token == (int64_t)0xDEADC0DE);

    lx_push_number(s, main, 999.0);
    int nResults2 = 0;
    int status2 = lx_resume(s, main, 1, &nResults2);
    assert(status2 == LX_RESUME_OK);
    assert(nResults2 == 1);

    int ok = 0;
    double v = lx_to_number(s, main, 1, &ok);
    assert(ok && v == 999.0);

    lx_close(s);
    printf("PASS test_native_suspend_resume\n");
}

/* ------------------------------------------------------------------ */
/* Test: Ref lifecycle                                                  */
/* ------------------------------------------------------------------ */

static void test_ref_lifecycle(void) {
    lx_State s  = lx_newstate(test_upcall);
    lx_Thread t = lx_main_thread(s);

    lx_newtable(s, t, 0, 0);
    int ref = lx_ref(s, t, -1);
    lx_pop(s, t, 1);

    lx_push_ref(s, t, ref);
    assert(lx_type(s, t, -1) == LX_TTABLE);
    lx_pop(s, t, 1);

    lx_unref(s, ref);

    lx_gc_collect(s);

    lx_close(s);
    printf("PASS test_ref_lifecycle\n");
}

/* ------------------------------------------------------------------ */
/* Test: string roundtrip                                              */
/* ------------------------------------------------------------------ */

static void test_string_roundtrip(void) {
    lx_State s = lx_newstate(test_upcall);
    lx_Thread t = lx_main_thread(s);

    const char* hello = "hello, \0 world";
    size_t hlen = 14;
    lx_push_lstring(s, t, hello, hlen);

    char buf[64] = {0};
    size_t len = 0;
    int ok = lx_to_lstring(s, t, -1, buf, sizeof(buf), &len);
    assert(ok == 1);
    assert(len == hlen);
    assert(memcmp(buf, hello, hlen) == 0);

    lx_pop(s, t, 1);
    lx_close(s);
    printf("PASS test_string_roundtrip\n");
}

/* ------------------------------------------------------------------ */
/* main                                                                 */
/* ------------------------------------------------------------------ */

int main(void) {
    test_basic_resume();
    test_error_becomes_status();
    test_native_return();
    test_native_fail();
    test_native_suspend_resume();
    test_ref_lifecycle();
    test_string_roundtrip();
    printf("All lx_test.c tests PASSED\n");
    return 0;
}
