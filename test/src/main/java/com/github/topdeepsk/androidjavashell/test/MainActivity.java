package com.github.topdeepsk.androidjavashell.test;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import com.github.topdeepsk.androidjavashell.service.JavaShellRemoteService;
import com.github.topdeepsk.androidjavashell.service.JavaShellService;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try (var s = JavaShellService.createUseRoot(this).createService(TestService.class)) {
            Log.d("MainActivity", s.getService().getString());
            s.getService().throwException();
        } catch (Throwable e) {
            Log.d("MainActivity", "throwException", e);
        }
    }

    public static class TestService implements JavaShellRemoteService {
        public String getString() {
            return "测试字符串".repeat(1000);
        }

        public void throwException() {
            throw new RuntimeException("测试抛出异常");
        }
    }
}
