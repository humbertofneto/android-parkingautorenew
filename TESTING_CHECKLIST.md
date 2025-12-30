# Quick Testing Checklist

## Build & Deploy on Windows

```bash
# On your macOS machine, in the project root
./gradlew assembleDebug

# Push the APK to Windows device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Or, start fresh install
adb uninstall com.example.parkingautorenew
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Capture Logs in Real-Time

### Option 1: Interactive Logcat (Best for Debugging)
```bash
# Start fresh logs
adb logcat --clear

# Watch logs as they happen (all MainActivity and PageBridge logs)
adb logcat -v threadtime | grep -E "(MainActivity|PageBridge)"
```

### Option 2: Save Logs to File
```bash
# Capture logs to file
adb logcat -v threadtime > ~/Desktop/app_logs.txt &

# After testing, retrieve logs
kill %1
cat ~/Desktop/app_logs.txt | grep -E "(MainActivity|PageBridge)"
```

### Option 3: Android Studio Logcat
- Connect device via USB
- Open `View > Tool Windows > Logcat`
- Filter by tag: `MainActivity|PageBridge`
- Click the record icon to start capturing

## Test Sequence

1. **Startup Test**
   - Clear logs: `adb logcat --clear`
   - Start app
   - Look for: `onCreate() START` → `Layout inflated` → `UI elements found` → `initializeWebView() START/COMPLETE`
   - ✅ If all appear, startup is good
   - ❌ If missing, app crashes during init

2. **First URL Load Test**
   - Enter: `https://www.offstreet.io/location/LWLN9BUO`
   - Click: **GET INFO**
   - Look for:
     - `GET INFO clicked - URL: https://...`
     - `New URL detected. Loading: https://...`
     - `onPageStarted: https://...`
     - `onPageFinished: https://... - Ready to extract data`
     - `extractPageInfo() START - Page attempt #1`
     - `evaluateJavascript callback - Result: ...`
     - `onPageInfo RECEIVED` ← Most important!
   - ✅ If `onPageInfo RECEIVED` appears, JavaScript works!
   - ❌ If it doesn't appear, JavaScript isn't executing

3. **Dynamic Page Capture Test**
   - While on page 1 of SPA, click a button/link to navigate to page 2 (within the SPA)
   - Click: **GET INFO** again
   - Look for:
     - `Same URL. Capturing current DOM state...`
     - `extractPageInfo() START - Page attempt #2`
     - `onPageInfo RECEIVED`
     - JSON should show `"page": 2` with page 2's elements
   - ✅ If page number changes in JSON, multi-page capture works!
   - ❌ If still shows `"page": 1`, WebView has old DOM

4. **Keyboard Test**
   - Click in the URL input field
   - Look for:
     - `urlInput gained focus`
     - `showKeyboard() called`
     - `urlInput focused: true, hasFocus: true`
     - `showSoftInput result: true` ← Should be true!
     - `Alternative showSoftInput called with SHOW_FORCED`
   - ✅ If result is `true`, check if keyboard appears on device
   - ❌ If result is `false`, focus/permission issue

5. **Error Test**
   - Enter invalid URL: `https://invalid-url-that-does-not-exist-12345.com`
   - Click: **GET INFO**
   - Look for:
     - `WebView error - URL: ...`
     - Error code and description should appear
   - ✅ If error logged, error handling works

## Key Logs to Search For

| Search Term | What It Means |
|-------------|--------------|
| `onPageFinished` | WebView successfully loaded a page |
| `onPageInfo RECEIVED` | JavaScript executed AND called Android method |
| `evaluateJavascript callback` | JavaScript injection completed |
| `showSoftInput result: true` | Keyboard should be visible |
| `showSoftInput result: false` | Keyboard won't show (focus issue?) |
| `WebView error` | Network or resource loading failed |
| `Error message:` | JavaScript threw an exception |

## Expected Log Order (Ideal Case)

```
D/MainActivity: GET INFO clicked - URL: https://www.offstreet.io/location/LWLN9BUO
D/MainActivity: New URL detected. Loading: https://www.offstreet.io/location/LWLN9BUO
D/MainActivity: loadUrl() called for: https://www.offstreet.io/location/LWLN9BUO
D/MainActivity: Delay 2000ms completed, calling extractPageInfo()
D/MainActivity: extractPageInfo() START - Page attempt #1
D/MainActivity: Calling webView.evaluateJavascript()...
D/MainActivity: evaluateJavascript callback - Result: null
D/PageBridge: === onPageInfo RECEIVED ===
D/PageBridge: JSON length: 2341 chars
D/PageBridge: Full JSON: {"page":1,"title":"...","url":"...","inputs":[...],...}
D/PageBridge: captureCount incremented to: 1
D/PageBridge: UI updated with captured page #1
```

## Troubleshooting

### Problem: No PageBridge logs at all
**Solution 1**: JavaScript might not be enabled
- Check: `WebView settings applied: JS enabled=true`

**Solution 2**: WebView might not have onPageFinished called
- Check for: `onPageFinished: <url>`

**Solution 3**: Android bridge might not be registered
- Check: `PageBridge interface added to WebView`

### Problem: onPageInfo shows page 1 twice
**Solution**: WebView is caching old DOM
- Try: Clear cache before loading: `webView.clearCache(true)`
- Or: Use `loadUrl(url)` with POST instead of cached GET

### Problem: Keyboard doesn't appear
**Solution 1**: Focus not on EditText
- Check: `urlInput focused: true`
- Add: `urlInput.requestFocus()` before showing keyboard

**Solution 2**: System keyboard disabled
- Check manifest for conflicting settings

**Solution 3**: Try SHOW_FORCED instead of SHOW_IMPLICIT
- Already implemented in code

## ADB Shortcuts

```bash
# Clear logs
adb logcat --clear

# Kill app
adb shell am force-stop com.example.parkingautorenew

# Restart app
adb shell am start -n com.example.parkingautorenew/.MainActivity

# Continuous filtered logs (auto-restart if app crashes)
adb logcat -v threadtime *:S MainActivity*:D PageBridge*:D
```

## Next Steps After Investigation

1. **If JavaScript works** → Debug why page 2 still shows page 1 data
2. **If JavaScript doesn't work** → Fix JavaScript injection or bridge setup
3. **If keyboard works** → Good! Monitor if it stays visible during interaction
4. **If keyboard doesn't work** → Add `urlInput.requestFocus()` before showing

---

**Expected Outcome**: Logs will pinpoint exactly where the issue is. Once we know:
- Where page capture fails (WebView? JS injection? Bridge?)
- Where keyboard fails (focus? IMM? permissions?)

We can implement targeted fixes instead of guessing.
