// JNI bridge for the native macOS glass path. Each piece is one NSGlassEffectView
// inserted in a container view below the AWT content view, so the window's glass
// material shows the desktop through the transparent Compose pipeline.

#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>

#import <AppKit/AppKit.h>
#import <QuartzCore/QuartzCore.h>
#import <objc/runtime.h>

#include <dlfcn.h>

#include <algorithm>
#include <map>
#include <string>
#include <vector>

namespace {

typedef jboolean (*JAWTGetAWTProc)(JNIEnv *, JAWT *);

struct PieceSpec {
    std::string key;
    double x = 0;
    double y = 0;
    double width = 0;
    double height = 0;
    double cornerRadius = 0;
    int style = 0;
    bool hasTint = false;
    NSColor *tint = nil;
};

struct WindowState {
    NSWindow *window = nil;
    NSView *contentView = nil;
    NSView *container = nil;
    NSView *base = nil;
    std::map<std::string, NSView *> pieces;
};

std::map<long, WindowState> gWindows;
long gNextHandle = 1;

const double kWindowCornerRadius = 26.0;

template <typename Fn>
void RunOnMain(Fn fn) {
    if ([NSThread isMainThread]) {
        fn();
        return;
    }
    dispatch_sync(dispatch_get_main_queue(), ^{
      fn();
    });
}

NSView *PassThroughHitTest(id self, SEL _cmd, NSPoint point) {
    return nil;
}

Class GlassViewClass() {
    static Class cls = Nil;
    static bool resolved = false;
    if (resolved) return cls;
    resolved = true;
    Class base = NSClassFromString(@"NSGlassEffectView");
    if (!base) return Nil;
    cls = objc_allocateClassPair(base, "StrideGlassView", 0);
    if (!cls) return Nil;
    class_addMethod(cls, @selector(hitTest:), (IMP)PassThroughHitTest, "@24@0:8{CGPoint=dd}16");
    objc_registerClassPair(cls);
    return cls;
}

Class ContainerViewClass() {
    static Class cls = Nil;
    static bool resolved = false;
    if (resolved) return cls;
    resolved = true;
    cls = objc_allocateClassPair([NSView class], "StrideGlassContainer", 0);
    if (!cls) return Nil;
    class_addMethod(cls, @selector(hitTest:), (IMP)PassThroughHitTest, "@24@0:8{CGPoint=dd}16");
    objc_registerClassPair(cls);
    return cls;
}

NSColor *ColorFromArgb(jint argb) {
    return [NSColor colorWithSRGBRed:((argb >> 16) & 0xFF) / 255.0
                               green:((argb >> 8) & 0xFF) / 255.0
                                blue:(argb & 0xFF) / 255.0
                               alpha:((argb >> 24) & 0xFF) / 255.0];
}

void SetProperty(NSView *view, NSString *key, id value) {
    @try {
        [view setValue:value forKey:key];
    } @catch (NSException *exception) {
        // The layer corner radius below still applies on an older material view.
    }
}

NSView *CreateGlassView(NSColor *tint, int style, CGFloat radius) {
    Class cls = GlassViewClass();
    if (!cls) return nil;
    NSView *view = [[cls alloc] initWithFrame:NSZeroRect];
    SetProperty(view, @"style", @(style));
    if (tint) SetProperty(view, @"tintColor", tint);
    view.wantsLayer = YES;
    view.layer.cornerRadius = radius;
    view.layer.masksToBounds = YES;
    SetProperty(view, @"cornerRadius", @(radius));
    return view;
}

NSWindow *WindowForComponent(JNIEnv *env, jobject component, JAWTGetAWTProc getAWT) {
    if (!getAWT || !component) return nil;
    JAWT awt;
    awt.version = JAWT_VERSION_1_4 | JAWT_MACOSX_USE_CALAYER;
    if (!getAWT(env, &awt)) return nil;
    JAWT_DrawingSurface *surface = awt.GetDrawingSurface(env, component);
    if (!surface) return nil;
    jint lock = surface->Lock(surface);
    if (lock & JAWT_LOCK_ERROR) {
        awt.FreeDrawingSurface(surface);
        return nil;
    }
    NSWindow *window = nil;
    JAWT_DrawingSurfaceInfo *info = surface->GetDrawingSurfaceInfo(surface);
    if (info && info->platformInfo) {
        @try {
            // platformInfo conforms to JAWT_SurfaceLayers; the AWT drawing layer is a
            // sublayer of the window's backing layer, whose delegate is the theme frame.
            id<JAWT_SurfaceLayers> layers = (__bridge id<JAWT_SurfaceLayers>)(void *)info->platformInfo;
            CALayer *layer = layers.windowLayer;
            for (int depth = 0; layer && depth < 16; depth++) {
                id delegate = layer.delegate;
                if ([delegate isKindOfClass:[NSView class]] && [(NSView *)delegate window]) {
                    window = [(NSView *)delegate window];
                    break;
                }
                layer = layer.superlayer;
            }
        } @catch (NSException *exception) {
            window = nil;
        }
    }
    if (info) surface->FreeDrawingSurfaceInfo(info);
    surface->Unlock(surface);
    awt.FreeDrawingSurface(surface);
    return window;
}

JAWTGetAWTProc ResolveJAWT(JNIEnv *env, jstring javaHome) {
    static JAWTGetAWTProc getAWT = nullptr;
    if (getAWT) return getAWT;
    // Resolve through the runtime's own libjawt: the app bundles a jlinked runtime, so
    // linking the build JDK's copy would put an absolute developer path in the binary.
    if (javaHome) {
        const char *home = env->GetStringUTFChars(javaHome, nullptr);
        if (home) {
            std::string path = std::string(home) + "/lib/libjawt.dylib";
            dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
            env->ReleaseStringUTFChars(javaHome, home);
        }
    }
    dlopen("libjawt.dylib", RTLD_NOW | RTLD_GLOBAL);
    getAWT = (JAWTGetAWTProc)dlsym(RTLD_DEFAULT, "JAWT_GetAWT");
    return getAWT;
}

WindowState *StateFor(long handle) {
    auto found = gWindows.find(handle);
    return found == gWindows.end() ? nullptr : &found->second;
}

void SyncContainer(WindowState &state) {
    if (!state.container || !state.contentView) return;
    NSRect frame = state.contentView.frame;
    state.container.frame = frame;
    state.container.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    if (state.base) state.base.frame = state.container.bounds;
}

NSRect FrameFor(NSView *container, const PieceSpec &spec) {
    CGFloat height = container.bounds.size.height;
    CGFloat y = container.isFlipped ? spec.y : height - spec.y - spec.height;
    return NSMakeRect(spec.x, y, spec.width, spec.height);
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_stride_app_glass_NativeGlass_nativeSupported(JNIEnv *, jobject) {
    bool supported = false;
    RunOnMain([&] {
      if (@available(macOS 26.0, *)) {
          supported = GlassViewClass() != Nil;
      }
    });
    return supported ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_stride_app_glass_NativeGlass_nativeAttach(JNIEnv *env, jobject, jobject component, jstring javaHome) {
    JAWTGetAWTProc getAWT = ResolveJAWT(env, javaHome);
    NSWindow *window = WindowForComponent(env, component, getAWT);
    if (!window) return 0;

    long handle = 0;
    RunOnMain([&] {
      if (GlassViewClass() == Nil) return;
      [window setOpaque:NO];
      [window setBackgroundColor:[NSColor clearColor]];
      NSView *contentView = window.contentView;
      NSView *parent = contentView.superview;
      if (!parent) return;
      WindowState state;
      state.window = window;
      state.contentView = contentView;
      Class containerClass = ContainerViewClass();
      NSView *container = containerClass ? [[containerClass alloc] initWithFrame:contentView.frame]
                                         : [[NSView alloc] initWithFrame:contentView.frame];
      container.wantsLayer = YES;
      container.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
      // Below the AWT content view: the Compose canvas stays the top layer and paints over
      // the glass wherever it is not transparent.
      [parent addSubview:container positioned:NSWindowBelow relativeTo:contentView];
      NSView *base = CreateGlassView(nil, 0, kWindowCornerRadius);
      if (base) {
          base.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
          base.frame = container.bounds;
          [container addSubview:base positioned:NSWindowBelow relativeTo:nil];
      }
      state.container = container;
      state.base = base;
      handle = gNextHandle++;
      gWindows[handle] = state;
    });
    return handle;
}

extern "C" JNIEXPORT void JNICALL
Java_stride_app_glass_NativeGlass_nativeDetach(JNIEnv *, jobject, jlong handle) {
    RunOnMain([&] {
      auto found = gWindows.find(handle);
      if (found == gWindows.end()) return;
      WindowState &state = found->second;
      if (state.base && state.base.superview) [state.base removeFromSuperview];
      for (auto &entry : state.pieces) {
          if (entry.second.superview) [entry.second removeFromSuperview];
      }
      if (state.container && state.container.superview) [state.container removeFromSuperview];
      gWindows.erase(found);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_stride_app_glass_NativeGlass_nativeSync(JNIEnv *env, jobject, jlong handle, jobjectArray keys,
                                             jfloatArray xs, jfloatArray ys, jfloatArray widths,
                                             jfloatArray heights, jfloatArray radii, jintArray styles,
                                             jintArray tints) {
    WindowState *found = StateFor(handle);
    if (!found || !keys) return 0;
    jsize count = env->GetArrayLength(keys);
    std::vector<PieceSpec> specs;
    specs.reserve(count);
    jfloat *xBuf = env->GetFloatArrayElements(xs, nullptr);
    jfloat *yBuf = env->GetFloatArrayElements(ys, nullptr);
    jfloat *wBuf = env->GetFloatArrayElements(widths, nullptr);
    jfloat *hBuf = env->GetFloatArrayElements(heights, nullptr);
    jfloat *rBuf = env->GetFloatArrayElements(radii, nullptr);
    jint *styleBuf = env->GetIntArrayElements(styles, nullptr);
    jint *tintBuf = env->GetIntArrayElements(tints, nullptr);
    for (jsize index = 0; index < count; index++) {
        auto key = (jstring)env->GetObjectArrayElement(keys, index);
        if (!key) continue;
        const char *chars = env->GetStringUTFChars(key, nullptr);
        PieceSpec spec;
        if (chars) spec.key = chars;
        if (chars) env->ReleaseStringUTFChars(key, chars);
        env->DeleteLocalRef(key);
        if (spec.key.empty()) continue;
        spec.x = xBuf[index];
        spec.y = yBuf[index];
        spec.width = wBuf[index];
        spec.height = hBuf[index];
        spec.cornerRadius = rBuf[index];
        spec.style = styleBuf[index];
        jint tint = tintBuf[index];
        if (tint != 0) {
            spec.hasTint = true;
            spec.tint = ColorFromArgb(tint);
        }
        specs.push_back(spec);
    }
    env->ReleaseFloatArrayElements(xs, xBuf, JNI_ABORT);
    env->ReleaseFloatArrayElements(ys, yBuf, JNI_ABORT);
    env->ReleaseFloatArrayElements(widths, wBuf, JNI_ABORT);
    env->ReleaseFloatArrayElements(heights, hBuf, JNI_ABORT);
    env->ReleaseFloatArrayElements(radii, rBuf, JNI_ABORT);
    env->ReleaseIntArrayElements(styles, styleBuf, JNI_ABORT);
    env->ReleaseIntArrayElements(tints, tintBuf, JNI_ABORT);

    jint applied = 0;
    RunOnMain([&] {
      auto located = gWindows.find(handle);
      if (located == gWindows.end()) return;
      WindowState &state = located->second;
      if (!state.container) return;
      SyncContainer(state);
      [CATransaction begin];
      [CATransaction setDisableActions:YES];
      std::map<std::string, bool> seen;
      for (const PieceSpec &spec : specs) {
          seen[spec.key] = true;
          NSView *view = state.pieces[spec.key];
          bool created = false;
          if (!view) {
              view = CreateGlassView(spec.hasTint ? spec.tint : nil, spec.style, spec.cornerRadius);
              if (!view) continue;
              state.pieces[spec.key] = view;
              created = true;
          }
          if (view.superview != state.container) {
              if (view.superview) [view removeFromSuperview];
              if (state.base && state.base.superview == state.container) {
                  [state.container addSubview:view positioned:NSWindowAbove relativeTo:state.base];
              } else {
                  [state.container addSubview:view];
              }
          }
          if (!created) {
              SetProperty(view, @"style", @(spec.style));
              SetProperty(view, @"tintColor", spec.hasTint ? (id)spec.tint : (id)[NSColor clearColor]);
              SetProperty(view, @"cornerRadius", @(spec.cornerRadius));
              view.layer.cornerRadius = spec.cornerRadius;
          }
          view.autoresizingMask = NSViewNotSizable;
          view.frame = FrameFor(state.container, spec);
          applied++;
      }
      for (auto it = state.pieces.begin(); it != state.pieces.end();) {
          if (seen.count(it->first) == 0) {
              if (it->second.superview) [it->second removeFromSuperview];
              it = state.pieces.erase(it);
          } else {
              ++it;
          }
      }
      [CATransaction commit];
    });
    return applied;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_stride_app_glass_NativeGlass_nativeState(JNIEnv *env, jobject, jlong handle) {
    std::vector<double> values;
    RunOnMain([&] {
      auto found = gWindows.find(handle);
      NSInteger windowCount = (NSInteger)[[NSApp windows] count];
      if (found == gWindows.end()) {
          values = {0.0, (double)windowCount, 0.0, 0.0};
          return;
      }
      WindowState &state = found->second;
      SyncContainer(state);
      values.push_back([state.window isOpaque] ? 1.0 : 0.0);
      values.push_back((double)windowCount);
      values.push_back(state.base ? 1.0 : 0.0);
      std::map<std::string, NSView *> sorted(state.pieces.begin(), state.pieces.end());
      values.push_back((double)sorted.size());
      for (auto &entry : sorted) {
          NSRect frame = entry.second.frame;
          values.push_back(frame.origin.x);
          values.push_back(frame.origin.y);
          values.push_back(frame.size.width);
          values.push_back(frame.size.height);
          values.push_back(entry.second.layer ? entry.second.layer.cornerRadius : 0.0);
      }
    });
    jdoubleArray result = env->NewDoubleArray((jsize)values.size());
    if (result && !values.empty()) env->SetDoubleArrayRegion(result, 0, (jsize)values.size(), values.data());
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_stride_app_glass_NativeGlass_nativeStateKeys(JNIEnv *env, jobject, jlong handle) {
    std::vector<std::string> keys;
    RunOnMain([&] {
      auto found = gWindows.find(handle);
      if (found == gWindows.end()) return;
      std::map<std::string, NSView *> sorted(found->second.pieces.begin(), found->second.pieces.end());
      for (auto &entry : sorted) keys.push_back(entry.first);
    });
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray((jsize)keys.size(), stringClass, nullptr);
    for (jsize index = 0; index < (jsize)keys.size(); index++) {
        jstring value = env->NewStringUTF(keys[index].c_str());
        env->SetObjectArrayElement(result, index, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_stride_app_glass_NativeGlass_nativeBeginDrag(JNIEnv *, jobject, jlong handle) {
    RunOnMain([&] {
      WindowState *state = StateFor(handle);
      if (!state || !state->window) return;
      NSWindow *window = state->window;
      NSEvent *event = [NSApp currentEvent];
      if (!event || event.type != NSEventTypeLeftMouseDown) {
          // The Compose press may arrive after the AppKit event was consumed; a synthetic
          // click at the pointer still starts the window drag loop.
          event = [NSEvent mouseEventWithType:NSEventTypeLeftMouseDown
                                    location:[NSEvent mouseLocation]
                               modifierFlags:0
                                   timestamp:[[NSProcessInfo processInfo] systemUptime]
                                windowNumber:window.windowNumber
                                     context:nil
                                  eventNumber:0
                                   clickCount:1
                                     pressure:1.0];
      }
      [window performWindowDragWithEvent:event];
    });
}

extern "C" JNIEXPORT void JNICALL
Java_stride_app_glass_NativeGlass_nativeZoom(JNIEnv *, jobject, jlong handle) {
    RunOnMain([&] {
      WindowState *state = StateFor(handle);
      if (state && state->window) [state->window performZoom:nil];
    });
}
