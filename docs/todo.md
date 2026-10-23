  - fix this:

    ```
    The following libraries used by project ':app' depend on legacy support libraries. To disable Jetifier, you will need to use AndroidX-supported versions of these libraries.
            org.fossify:commons:7.1.3 (Project ':app', configuration 'fossDebugAndroidTestCompileClasspath' -> org.fossify:commons:7.1.3 -> com.github.aritraroy:patternLockView:a90b0d4bf0 -> com.github.aritraroy.patternLockView:patternlockview:a90b0d4bf0 -> com.android.support:appcompat-v7:25.3.0)
    ```

  - maybe no schema JSON is committed in the repository? if so, add it
