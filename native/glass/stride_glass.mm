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
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <unistd.h>
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

/** A layer whose opaque flag and background the bridge changed; restored on detach or failure. */
struct LayerOriginal {
    CALayer *__strong layer = nil;
    BOOL opaque = NO;
    CGColorRef background = nullptr;
};

struct WindowState {
    NSWindow *window = nil;
    NSView *contentView = nil;
    NSView *container = nil;
    NSView *base = nil;
    std::map<std::string, NSView *__strong> pieces;
    std::vector<LayerOriginal> layerOriginals;
    BOOL windowOpaque = YES;
    NSColor *__strong windowBackground = nil;
    id __strong dragMonitor = nil;
    // Drag rectangles in window points from the top-left corner.
    std::vector<NSRect> dragRects;
};

/** The last detached window, so the test can prove the restore happened. */
struct DetachedState {
    long handle = 0;
    BOOL windowOpaque = YES;
    BOOL hasDrawingLayer = NO;
    BOOL drawingOpaque = NO;
    BOOL ancestorsClear = NO;
    double drawingBackgroundAlpha = 0.0;
    int restoredLayers = 0;
};

std::map<long, WindowState> gWindows;
DetachedState gDetached;

const double kWindowCornerRadius = 26.0;
const int kAnswerTimeoutMillis = 1000;

JavaVM *gVm = nullptr;
jobject gAppearanceCallback = nullptr;
id gAppearanceObserver = nil;

/**
 * The AppKit thread runs AWT's own nested run loop in this mode while it waits for the event
 * dispatch thread (`+[ThreadUtilities javaRunLoopMode]` in the runtime). GCD's main queue is not
 * serviced in that mode, so a Java thread waiting for the main queue deadlocks against it.
 */
static NSString *const kAwtRunLoopMode = @"AWTRunLoopMode";

/** Modes the AppKit thread may be running in; AWT's nested mode is the one that matters here. */
NSArray<NSString *> *AppKitRunLoopModes() {
    static NSArray<NSString *> *modes = nil;
    static std::once_flag once;
    std::call_once(once, [] {
      modes = @[
          NSDefaultRunLoopMode,
          NSModalPanelRunLoopMode,
          NSEventTrackingRunLoopMode,
          kAwtRunLoopMode,
      ];
    });
    return modes;
}

/**
 * Hands [fn] to the AppKit run loop without waiting. CFRunLoopPerformBlock delivers the blocks
 * in the order they were added, so attach → sync → detach keep their order, and it reaches the
 * AWT nested run loop where the main dispatch queue does not.
 */
template <typename Fn>
void OnAppKitRunLoop(Fn fn) {
    CFRunLoopRef loop = CFRunLoopGetMain();
    CFRunLoopPerformBlock(loop, (__bridge CFTypeRef)AppKitRunLoopModes(), ^{
      fn();
    });
    CFRunLoopWakeUp(loop);
}

/** Signalled by a run-loop block so a Java caller can wait with a bound instead of forever. */
struct Completion {
    std::mutex mutex;
    std::condition_variable condition;
    bool done = false;

    void Finish() {
        std::lock_guard<std::mutex> lock(mutex);
        done = true;
        condition.notify_all();
    }

    bool Wait() {
        std::unique_lock<std::mutex> lock(mutex);
        return condition.wait_for(lock, std::chrono::milliseconds(kAnswerTimeoutMillis), [this] {
            return done;
        });
    }
};

/**
 * The synchronous form, only for queries whose answer the caller really needs (state reads,
 * the appearance query). The caller waits at most kAnswerTimeoutMillis: if the AppKit thread is
 * itself waiting for this thread, the query returns false and the caller falls back instead of
 * joining the deadlock.
 */
template <typename Fn>
bool AskAppKitRunLoop(const char *what, Fn fn) {
    if ([NSThread isMainThread]) {
        fn();
        return true;
    }
    auto completion = std::make_shared<Completion>();
    auto work = std::make_shared<Fn>(fn);
    OnAppKitRunLoop([completion, work] {
      (*work)();
      completion->Finish();
    });
    if (!completion->Wait()) {
        fprintf(
            stderr,
            "stride-glass: %s did not reach the AppKit run loop within %d ms\n",
            what,
            kAnswerTimeoutMillis);
        return false;
    }
    return true;
}

NSView *PassThroughHitTest(id self, SEL _cmd, NSPoint point) {
    return nil;
}

/**
 * `- (NSView *)hitTest:(NSPoint)point` for the current architecture: return 8 bytes, self at 0,
 * _cmd at 8, CGPoint at 16, 32 bytes of arguments on 64-bit. Built from @encode so the offsets
 * follow the running ABI instead of the old 32-bit literals.
 */
const char *HitTestTypeEncoding() {
    static std::string encoding;
    if (encoding.empty()) {
        char buffer[128];
        snprintf(
            buffer,
            sizeof(buffer),
            "@%zu@0:%zu%s%zu",
            sizeof(id) + sizeof(SEL) + sizeof(NSPoint),
            sizeof(id),
            @encode(NSPoint),
            sizeof(id) + sizeof(SEL));
        encoding = buffer;
    }
    return encoding.c_str();
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
    class_addMethod(cls, @selector(hitTest:), (IMP)PassThroughHitTest, HitTestTypeEncoding());
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
    class_addMethod(cls, @selector(hitTest:), (IMP)PassThroughHitTest, HitTestTypeEncoding());
    objc_registerClassPair(cls);
    return cls;
}

NSColor *ColorFromArgb(jint argb) {
    return [NSColor colorWithSRGBRed:((argb >> 16) & 0xFF) / 255.0
                               green:((argb >> 8) & 0xFF) / 255.0
                                blue:(argb & 0xFF) / 255.0
                               alpha:((argb >> 24) & 0xFF) / 255.0];
}

bool SetProperty(NSView *view, NSString *key, id value) {
    @try {
        [view setValue:value forKey:key];
        return true;
    } @catch (NSException *exception) {
        return false;
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

/** 0 = unknown, 1 = light, 2 = dark. NSApp stays nil until AWT installs its subclass. */
jint CurrentAppearanceValue() {
    // Never ask for the shared application here: creating a plain NSApplication before AWT
    // does would prevent NSApplicationAWT (and its delegate and menu bar) from being installed.
    NSApplication *app = NSApp;
    if (!app) return 0;
    NSAppearance *appearance = app.effectiveAppearance;
    if (!appearance) return 0;
    NSAppearanceName match =
        [appearance bestMatchFromAppearancesWithNames:@[ NSAppearanceNameAqua, NSAppearanceNameDarkAqua ]];
    if ([match isEqualToString:NSAppearanceNameDarkAqua]) return 2;
    if ([match isEqualToString:NSAppearanceNameAqua]) return 1;
    return [appearance.name isEqualToString:NSAppearanceNameDarkAqua] ? 2 : 1;
}

void NotifyAppearance() {
    if (!gVm || !gAppearanceCallback) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_8) != JNI_OK) {
        if (gVm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return;
        attached = true;
    }
    jclass cls = env->GetObjectClass(gAppearanceCallback);
    if (cls) {
        jmethodID run = env->GetMethodID(cls, "run", "()V");
        if (run) env->CallVoidMethod(gAppearanceCallback, run);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(cls);
    }
    if (attached) gVm->DetachCurrentThread();
}

/**
 * The Skiko drawing layer: a CAMetalLayer under the content view. AWT's own Metal layer
 * (`MTLLayer`) is skipped: it paints the Swing backing store, while Skiko adds its layer below.
 */
CALayer *FindDrawingLayer(CALayer *layer, int depth) {
    if (!layer || depth > 12) return nil;
    for (CALayer *sub in layer.sublayers) {
        if (CALayer *found = FindDrawingLayer(sub, depth + 1)) return found;
    }
    NSString *name = NSStringFromClass([layer class]);
    Class metal = NSClassFromString(@"CAMetalLayer");
    if (metal && [layer isKindOfClass:metal] && ![name isEqualToString:@"MTLLayer"]) return layer;
    if ([name containsString:@"Skia"]) return layer;
    return nil;
}

void DescribeLayer(CALayer *layer, int depth, std::string &out) {
    if (!layer || depth > 10) return;
    NSString *name = NSStringFromClass([layer class]);
    CGColorRef background = layer.backgroundColor;
    char line[512];
    snprintf(
        line,
        sizeof(line),
        "%*s%s opaque=%d background=%.2f\n",
        depth * 2,
        "",
        name.UTF8String ? name.UTF8String : "?",
        layer.isOpaque ? 1 : 0,
        background ? CGColorGetAlpha(background) : 0.0);
    out += line;
    for (CALayer *sub in layer.sublayers) DescribeLayer(sub, depth + 1, out);
}

CALayer *FindDrawingLayerInView(NSView *view, int depth) {
    if (!view || depth > 12) return nil;
    if (CALayer *found = FindDrawingLayer(view.layer, 0)) return found;
    for (NSView *sub in view.subviews) {
        if (CALayer *found = FindDrawingLayerInView(sub, depth + 1)) return found;
    }
    return nil;
}

bool IsTransparentLayer(CALayer *layer) {
    if (!layer) return false;
    if (layer.isOpaque) return false;
    CGColorRef background = layer.backgroundColor;
    if (background && CGColorGetAlpha(background) > 0.001) return false;
    return true;
}

/** Every layer above [layer] up to the window frame must be non-opaque and unpainted. */
bool AncestorsAreTransparent(CALayer *layer, int *count) {
    bool clear = true;
    *count = 0;
    for (CALayer *current = layer.superlayer; current; current = current.superlayer) {
        (*count)++;
        if (current.isOpaque) clear = false;
        CGColorRef background = current.backgroundColor;
        if (background && CGColorGetAlpha(background) > 0.001) clear = false;
        id delegate = current.delegate;
        if ([delegate isKindOfClass:[NSView class]] && [(NSView *)delegate isOpaque]) clear = false;
    }
    id host = layer.delegate;
    if ([host isKindOfClass:[NSView class]] && [(NSView *)host isOpaque]) clear = false;
    return clear;
}

/** Remember one layer's original state once and clear it; a later detach restores all of them. */
void RecordLayer(WindowState &state, CALayer *layer) {
    for (const LayerOriginal &original : state.layerOriginals) {
        if (original.layer == layer) return;
    }
    LayerOriginal original;
    original.layer = layer;
    original.opaque = layer.isOpaque;
    original.background = layer.backgroundColor;
    if (original.background) CGColorRetain(original.background);
    state.layerOriginals.push_back(original);
    layer.opaque = NO;
    layer.backgroundColor = nil;
}

/**
 * AWT's window backing layer (MTLLayer) stays opaque even when the Skia layer is transparent,
 * which would hide the glass below. Clear the whole chain from the Skia layer up; the pixels
 * Skia draws already carry their own alpha.
 */
void MakeDrawingChainTransparent(WindowState &state) {
    if (!state.contentView) return;
    CALayer *drawing = FindDrawingLayerInView(state.contentView, 0);
    if (!drawing) return;
    RecordLayer(state, drawing);
    for (CALayer *current = drawing.superlayer; current; current = current.superlayer) {
        RecordLayer(state, current);
    }
}

/** Undo every transparency change: the layer flags and backgrounds and the window's own state. */
void RestoreTransparency(WindowState &state) {
    for (LayerOriginal &original : state.layerOriginals) {
        original.layer.opaque = original.opaque;
        original.layer.backgroundColor = original.background;
        if (original.background) {
            CGColorRelease(original.background);
            original.background = nullptr;
        }
    }
    state.layerOriginals.clear();
    if (state.window) {
        [state.window setOpaque:state.windowOpaque];
        [state.window setBackgroundColor:state.windowBackground];
    }
}

bool ContainerIsBelowContent(WindowState &state) {
    if (!state.container || !state.contentView) return false;
    NSView *parent = state.container.superview;
    if (!parent || parent != state.contentView.superview) return false;
    NSArray *siblings = parent.subviews;
    NSUInteger containerIndex = [siblings indexOfObjectIdenticalTo:state.container];
    NSUInteger contentIndex = [siblings indexOfObjectIdenticalTo:state.contentView];
    return containerIndex != NSNotFound && contentIndex != NSNotFound && containerIndex < contentIndex;
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

bool DragRectsContain(const std::vector<NSRect> &rects, CGFloat x, CGFloat topLeftY) {
    for (const NSRect &rect : rects) {
        if (x >= rect.origin.x && x < NSMaxX(rect) &&
            topLeftY >= rect.origin.y && topLeftY < NSMaxY(rect)) {
            return true;
        }
    }
    return false;
}

/** The event point is in window base coordinates (bottom-left); the reported rects are top-left. */
bool PointInDragRects(const WindowState &state, NSPoint windowPoint) {
    if (!state.container) return false;
    CGFloat topLeftY = state.container.bounds.size.height - windowPoint.y;
    return DragRectsContain(state.dragRects, windowPoint.x, topLeftY);
}

/** The system "double-click a window's title bar" action. */
void ApplyTitleDoubleClickAction(NSWindow *window) {
    NSString *action = [[NSUserDefaults standardUserDefaults] stringForKey:@"AppleActionOnDoubleClick"];
    if ([action caseInsensitiveCompare:@"Minimize"] == NSOrderedSame) {
        [window performMiniaturize:nil];
    } else if ([action caseInsensitiveCompare:@"None"] == NSOrderedSame) {
        return;
    } else {
        [window performZoom:nil];
    }
}

/**
 * A local mouse-down monitor on the main thread. The real event is passed to
 * performWindowDragWithEvent: so the drag starts from the actual click position, and the EDT
 * is never blocked. Only the left button matches the mask; other buttons fall through.
 */
void InstallDragMonitor(long handle, WindowState &state) {
    if (state.dragMonitor || !state.window) return;
    state.dragMonitor = [NSEvent
        addLocalMonitorForEventsMatchingMask:NSEventMaskLeftMouseDown
                                     handler:^NSEvent *(NSEvent *event) {
                                       auto found = gWindows.find(handle);
                                       if (found == gWindows.end()) return event;
                                       WindowState &current = found->second;
                                       if (!current.window || event.window != current.window) return event;
                                       if (!PointInDragRects(current, event.locationInWindow)) return event;
                                       if (event.clickCount >= 2) {
                                           ApplyTitleDoubleClickAction(current.window);
                                       } else {
                                           [current.window performWindowDragWithEvent:event];
                                       }
                                       return nil;
                                     }];
}

void RemoveDragMonitor(WindowState &state) {
    if (!state.dragMonitor) return;
    [NSEvent removeMonitor:state.dragMonitor];
    state.dragMonitor = nil;
}

int ViewStyle(NSView *view) {
    @try {
        id value = [view valueForKey:@"style"];
        return value ? [value intValue] : 0;
    } @catch (NSException *exception) {
        return 0;
    }
}

jint ViewTint(NSView *view) {
    @try {
        id value = [view valueForKey:@"tintColor"];
        NSColor *color = [value isKindOfClass:[NSColor class]] ? (NSColor *)value : nil;
        if (!color) return 0;
        NSColor *srgb = [color colorUsingColorSpace:[NSColorSpace sRGBColorSpace]];
        if (!srgb) return 0;
        CGFloat red = 0, green = 0, blue = 0, alpha = 0;
        [srgb getRed:&red green:&green blue:&blue alpha:&alpha];
        auto channel = [](CGFloat component) -> jint { return (jint)lround(component * 255.0); };
        return (channel(alpha) << 24) | (channel(red) << 16) | (channel(green) << 8) | channel(blue);
    } @catch (NSException *exception) {
        return 0;
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    gVm = vm;
    return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT jint JNICALL
Java_stride_app_glass_NativeAppearance_nativeAppearance(JNIEnv *, jobject) {
    // Before AWT there is no application object: report unknown without touching AppKit.
    if (!NSApp) return 0;
    auto value = std::make_shared<std::atomic<jint>>(0);
    AskAppKitRunLoop("appearance query", [value] {
      value->store(CurrentAppearanceValue());
    });
    return value->load();
}

extern "C" JNIEXPORT jstring JNICALL
Java_stride_app_glass_NativeAppearance_nativeAppClassName(JNIEnv *env, jobject) {
    if (!NSApp) return env->NewStringUTF("");
    auto name = std::make_shared<std::string>();
    AskAppKitRunLoop("application class query", [name] {
      if (NSApp) {
          const char *utf8 = NSStringFromClass([NSApp class]).UTF8String;
          if (utf8) *name = utf8;
      }
    });
    jstring result = env->NewStringUTF(name->c_str());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_stride_app_glass_NativeAppearance_nativeStartObserver(JNIEnv *env, jobject, jobject runnable) {
    jobject callback = runnable ? env->NewGlobalRef(runnable) : nullptr;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    if (!callback) return JNI_FALSE;
    // Registration is a mutation: enqueue it and report that it was accepted. The observer
    // signal itself runs on the AppKit queue and attaches to the VM when it fires.
    OnAppKitRunLoop([callback] {
      if (gAppearanceObserver) {
          [[NSDistributedNotificationCenter defaultCenter] removeObserver:gAppearanceObserver];
          gAppearanceObserver = nil;
      }
      if (gAppearanceCallback && gVm) {
          JNIEnv *mainEnv = nullptr;
          if (gVm->GetEnv(reinterpret_cast<void **>(&mainEnv), JNI_VERSION_1_8) == JNI_OK) {
              mainEnv->DeleteGlobalRef(gAppearanceCallback);
          }
          gAppearanceCallback = nullptr;
      }
      gAppearanceCallback = callback;
      gAppearanceObserver = [[NSDistributedNotificationCenter defaultCenter]
          addObserverForName:@"AppleInterfaceThemeChangedNotification"
                      object:nil
                       queue:[NSOperationQueue mainQueue]
                  usingBlock:^(NSNotification *) {
                    NotifyAppearance();
                  }];
    });
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_stride_app_glass_NativeAppearance_nativeStopObserver(JNIEnv *, jobject) {
    OnAppKitRunLoop([] {
      if (gAppearanceObserver) {
          [[NSDistributedNotificationCenter defaultCenter] removeObserver:gAppearanceObserver];
          gAppearanceObserver = nil;
      }
      if (gAppearanceCallback && gVm) {
          JNIEnv *mainEnv = nullptr;
          if (gVm->GetEnv(reinterpret_cast<void **>(&mainEnv), JNI_VERSION_1_8) == JNI_OK) {
              mainEnv->DeleteGlobalRef(gAppearanceCallback);
          }
          gAppearanceCallback = nullptr;
      }
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_stride_app_glass_NativeGlass_nativeSupported(JNIEnv *, jobject) {
    // Only the OS version and a class lookup are needed, and both are thread-safe: this runs on
    // the event dispatch thread and must never wait for the AppKit thread. Cached for the same
    // reason (the answer cannot change in a process).
    static std::atomic<int> cached{-1};
    int value = cached.load();
    if (value < 0) {
        bool supported = false;
        if (@available(macOS 26.0, *)) {
            supported = NSClassFromString(@"NSGlassEffectView") != nil;
        }
        value = supported ? 1 : 0;
        cached.store(value);
    }
    return value != 0 ? JNI_TRUE : JNI_FALSE;
}

/**
 * The window lookup (JAWT) stays on the calling thread because JAWT requires its lock there;
 * everything that touches AppKit is enqueued. [handle] comes from Java, so no answer is needed
 * from the AppKit thread.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_stride_app_glass_NativeGlass_nativeAttach(JNIEnv *env, jobject, jlong handle, jobject component,
                                                jstring javaHome) {
    if (!component || handle == 0) return JNI_FALSE;
    JAWTGetAWTProc getAWT = ResolveJAWT(env, javaHome);
    NSWindow *window = WindowForComponent(env, component, getAWT);
    if (!window) return JNI_FALSE;

    OnAppKitRunLoop([handle, window] {
      if (GlassViewClass() == Nil) return;
      NSView *contentView = window.contentView;
      NSView *parent = contentView ? contentView.superview : nil;
      Class containerClass = ContainerViewClass();
      if (!contentView || !parent || !containerClass) return;
      NSView *container = [[containerClass alloc] initWithFrame:contentView.frame];
      if (!container) return;

      // Every precondition passed; only now is the window and its layer chain changed.
      WindowState state;
      state.window = window;
      state.contentView = contentView;
      state.windowOpaque = [window isOpaque];
      state.windowBackground = [window backgroundColor];
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
      [window setOpaque:NO];
      [window setBackgroundColor:[NSColor clearColor]];
      MakeDrawingChainTransparent(state);
      InstallDragMonitor(handle, state);
      gWindows[handle] = state;
    });
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_stride_app_glass_NativeGlass_nativeDetach(JNIEnv *, jobject, jlong handle) {
    OnAppKitRunLoop([handle] {
      auto found = gWindows.find(handle);
      if (found == gWindows.end()) return;
      WindowState &state = found->second;
      RemoveDragMonitor(state);
      if (state.base && state.base.superview) [state.base removeFromSuperview];
      for (auto &entry : state.pieces) {
          if (entry.second.superview) [entry.second removeFromSuperview];
      }
      if (state.container && state.container.superview) [state.container removeFromSuperview];
      int restoredLayers = (int)state.layerOriginals.size();
      RestoreTransparency(state);
      CALayer *drawing = state.contentView ? FindDrawingLayerInView(state.contentView, 0) : nil;
      int ancestorCount = 0;
      gDetached.handle = handle;
      gDetached.windowOpaque = state.window ? [state.window isOpaque] : YES;
      gDetached.hasDrawingLayer = drawing != nil;
      gDetached.drawingOpaque = drawing ? drawing.isOpaque : NO;
      gDetached.ancestorsClear = drawing ? AncestorsAreTransparent(drawing, &ancestorCount) : NO;
      gDetached.drawingBackgroundAlpha =
          drawing && drawing.backgroundColor ? CGColorGetAlpha(drawing.backgroundColor) : 0.0;
      gDetached.restoredLayers = restoredLayers;
      gWindows.erase(found);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_stride_app_glass_NativeGlass_nativeSync(JNIEnv *env, jobject, jlong handle, jobjectArray keys,
                                             jfloatArray xs, jfloatArray ys, jfloatArray widths,
                                             jfloatArray heights, jfloatArray radii, jintArray styles,
                                             jintArray tints) {
    if (!keys || !xs || !ys || !widths || !heights || !radii || !styles || !tints) return 0;
    jsize count = env->GetArrayLength(keys);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }
    if (env->GetArrayLength(xs) < count || env->GetArrayLength(ys) < count ||
        env->GetArrayLength(widths) < count || env->GetArrayLength(heights) < count ||
        env->GetArrayLength(radii) < count || env->GetArrayLength(styles) < count ||
        env->GetArrayLength(tints) < count) {
        return 0;
    }
    std::vector<PieceSpec> specs;
    specs.reserve(count);
    jfloat *xBuf = env->GetFloatArrayElements(xs, nullptr);
    jfloat *yBuf = env->GetFloatArrayElements(ys, nullptr);
    jfloat *wBuf = env->GetFloatArrayElements(widths, nullptr);
    jfloat *hBuf = env->GetFloatArrayElements(heights, nullptr);
    jfloat *rBuf = env->GetFloatArrayElements(radii, nullptr);
    jint *styleBuf = env->GetIntArrayElements(styles, nullptr);
    jint *tintBuf = env->GetIntArrayElements(tints, nullptr);
    bool buffersReady = xBuf && yBuf && wBuf && hBuf && rBuf && styleBuf && tintBuf;
    bool failed = !buffersReady;
    if (buffersReady) {
        for (jsize index = 0; index < count; index++) {
            auto key = (jstring)env->GetObjectArrayElement(keys, index);
            if (env->ExceptionCheck()) {
                failed = true;
                break;
            }
            if (!key) continue;
            const char *chars = env->GetStringUTFChars(key, nullptr);
            PieceSpec spec;
            if (chars) {
                spec.key = chars;
                env->ReleaseStringUTFChars(key, chars);
            }
            env->DeleteLocalRef(key);
            if (env->ExceptionCheck()) {
                failed = true;
                break;
            }
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
    }
    if (xBuf) env->ReleaseFloatArrayElements(xs, xBuf, JNI_ABORT);
    if (yBuf) env->ReleaseFloatArrayElements(ys, yBuf, JNI_ABORT);
    if (wBuf) env->ReleaseFloatArrayElements(widths, wBuf, JNI_ABORT);
    if (hBuf) env->ReleaseFloatArrayElements(heights, hBuf, JNI_ABORT);
    if (rBuf) env->ReleaseFloatArrayElements(radii, rBuf, JNI_ABORT);
    if (styleBuf) env->ReleaseIntArrayElements(styles, styleBuf, JNI_ABORT);
    if (tintBuf) env->ReleaseIntArrayElements(tints, tintBuf, JNI_ABORT);
    if (failed || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 0;
    }

    // Mutations never wait for the AppKit thread; the returned count means "accepted", which is
    // the number of pieces handed to the run loop.
    auto pending = std::make_shared<std::vector<PieceSpec>>(std::move(specs));
    OnAppKitRunLoop([handle, pending] {
      auto located = gWindows.find(handle);
      if (located == gWindows.end()) return;
      WindowState &state = located->second;
      if (!state.container) return;
      SyncContainer(state);
      MakeDrawingChainTransparent(state);
      [CATransaction begin];
      [CATransaction setDisableActions:YES];
      std::map<std::string, bool> seen;
      for (const PieceSpec &spec : *pending) {
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
    return (jint)pending->size();
}

extern "C" JNIEXPORT jint JNICALL
Java_stride_app_glass_NativeGlass_nativeSetDragRects(JNIEnv *env, jobject, jlong handle,
                                                     jfloatArray xs, jfloatArray ys,
                                                     jfloatArray widths, jfloatArray heights) {
    if (!xs || !ys || !widths || !heights) return 0;
    jsize count = env->GetArrayLength(xs);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }
    if (env->GetArrayLength(ys) < count || env->GetArrayLength(widths) < count ||
        env->GetArrayLength(heights) < count) {
        return 0;
    }
    jfloat *xBuf = env->GetFloatArrayElements(xs, nullptr);
    jfloat *yBuf = env->GetFloatArrayElements(ys, nullptr);
    jfloat *wBuf = env->GetFloatArrayElements(widths, nullptr);
    jfloat *hBuf = env->GetFloatArrayElements(heights, nullptr);
    if (!xBuf || !yBuf || !wBuf || !hBuf) {
        if (xBuf) env->ReleaseFloatArrayElements(xs, xBuf, JNI_ABORT);
        if (yBuf) env->ReleaseFloatArrayElements(ys, yBuf, JNI_ABORT);
        if (wBuf) env->ReleaseFloatArrayElements(widths, wBuf, JNI_ABORT);
        if (hBuf) env->ReleaseFloatArrayElements(heights, hBuf, JNI_ABORT);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 0;
    }
    std::vector<NSRect> rects;
    rects.reserve(count);
    for (jsize index = 0; index < count; index++) {
        rects.push_back(NSMakeRect(xBuf[index], yBuf[index], wBuf[index], hBuf[index]));
    }
    env->ReleaseFloatArrayElements(xs, xBuf, JNI_ABORT);
    env->ReleaseFloatArrayElements(ys, yBuf, JNI_ABORT);
    env->ReleaseFloatArrayElements(widths, wBuf, JNI_ABORT);
    env->ReleaseFloatArrayElements(heights, hBuf, JNI_ABORT);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }
    auto pending = std::make_shared<std::vector<NSRect>>(std::move(rects));
    OnAppKitRunLoop([handle, pending] {
      auto found = gWindows.find(handle);
      if (found == gWindows.end()) return;
      found->second.dragRects = *pending;
    });
    return (jint)pending->size();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_stride_app_glass_NativeGlass_nativeHitTestDragRegion(JNIEnv *, jobject, jlong handle, jdouble x,
                                                          jdouble y) {
    auto hit = std::make_shared<std::atomic<bool>>(false);
    AskAppKitRunLoop("drag hit test", [hit, handle, x, y] {
      auto found = gWindows.find(handle);
      if (found == gWindows.end()) return;
      hit->store(DragRectsContain(found->second.dragRects, (CGFloat)x, (CGFloat)y));
    });
    return hit->load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_stride_app_glass_NativeGlass_nativeDragMonitorCount(JNIEnv *, jobject) {
    auto count = std::make_shared<std::atomic<jint>>(0);
    AskAppKitRunLoop("drag monitor count", [count] {
      jint value = 0;
      for (auto &entry : gWindows) {
          if (entry.second.dragMonitor) value++;
      }
      count->store(value);
    });
    return count->load();
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_stride_app_glass_NativeGlass_nativeState(JNIEnv *env, jobject, jlong handle) {
    auto values = std::make_shared<std::vector<double>>();
    bool answered = AskAppKitRunLoop("native state query", [values, handle] {
      auto found = gWindows.find(handle);
      NSInteger windowCount = (NSInteger)[[NSApp windows] count];
      if (found == gWindows.end()) {
          if (gDetached.handle == handle) {
              *values = {
                  gDetached.windowOpaque ? 1.0 : 0.0,
                  (double)windowCount,
                  0.0,
                  0.0,
                  gDetached.hasDrawingLayer ? 1.0 : 0.0,
                  gDetached.drawingOpaque ? 1.0 : 0.0,
                  gDetached.drawingBackgroundAlpha,
                  gDetached.ancestorsClear ? 1.0 : 0.0,
                  0.0,
                  0.0,
                  0.0,
                  0.0,
                  0.0,
                  (double)gDetached.restoredLayers,
              };
              return;
          }
          *values = {0.0, (double)windowCount, 0.0, 0.0};
          return;
      }
      WindowState &state = found->second;
      SyncContainer(state);
      values->push_back([state.window isOpaque] ? 1.0 : 0.0);
      values->push_back((double)windowCount);
      values->push_back(state.base ? 1.0 : 0.0);
      std::map<std::string, NSView *> sorted(state.pieces.begin(), state.pieces.end());
      values->push_back((double)sorted.size());
      for (auto &entry : sorted) {
          NSRect frame = entry.second.frame;
          values->push_back(frame.origin.x);
          values->push_back(frame.origin.y);
          values->push_back(frame.size.width);
          values->push_back(frame.size.height);
          values->push_back(entry.second.layer ? entry.second.layer.cornerRadius : 0.0);
          values->push_back((double)ViewStyle(entry.second));
          values->push_back((double)(uint32_t)ViewTint(entry.second));
      }
      // The transparency chain of the Compose drawing layer: the value block is fixed-size so
      // the Java side can read it after the pieces.
      CALayer *drawing = FindDrawingLayerInView(state.contentView, 0);
      int ancestorCount = 0;
      bool ancestorsClear = drawing ? AncestorsAreTransparent(drawing, &ancestorCount) : false;
      NSColor *windowBackground = state.window.backgroundColor;
      values->push_back(drawing ? 1.0 : 0.0);
      values->push_back(drawing && drawing.isOpaque ? 1.0 : 0.0);
      values->push_back(drawing && drawing.backgroundColor
                               ? (double)CGColorGetAlpha(drawing.backgroundColor)
                               : 0.0);
      values->push_back(ancestorsClear ? 1.0 : 0.0);
      values->push_back((double)ancestorCount);
      values->push_back(windowBackground ? (double)windowBackground.alphaComponent : 0.0);
      values->push_back(ContainerIsBelowContent(state) ? 1.0 : 0.0);
      values->push_back(state.contentView.isOpaque ? 1.0 : 0.0);
      values->push_back(state.container && state.container.isOpaque ? 1.0 : 0.0);
      values->push_back(0.0);  // restored layers, only reported after detach
    });
    if (!answered) return nullptr;
    jdoubleArray result = env->NewDoubleArray((jsize)values->size());
    if (!result) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    if (!values->empty()) env->SetDoubleArrayRegion(result, 0, (jsize)values->size(), values->data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_stride_app_glass_NativeGlass_nativeStateKeys(JNIEnv *env, jobject, jlong handle) {
    auto keys = std::make_shared<std::vector<std::string>>();
    AskAppKitRunLoop("native state keys query", [keys, handle] {
      auto found = gWindows.find(handle);
      if (found == gWindows.end()) return;
      std::map<std::string, NSView *> sorted(found->second.pieces.begin(), found->second.pieces.end());
      for (auto &entry : sorted) keys->push_back(entry.first);
    });
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray((jsize)keys->size(), stringClass, nullptr);
    if (!result) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    for (jsize index = 0; index < (jsize)keys->size(); index++) {
        jstring value = env->NewStringUTF((*keys)[index].c_str());
        if (!value) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return result;
        }
        env->SetObjectArrayElement(result, index, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return result;
        }
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_stride_app_glass_NativeGlass_nativeLayerClass(JNIEnv *env, jobject, jlong handle) {
    auto name = std::make_shared<std::string>();
    AskAppKitRunLoop("layer class query", [name, handle] {
      auto found = gWindows.find(handle);
      if (found == gWindows.end()) return;
      CALayer *drawing = FindDrawingLayerInView(found->second.contentView, 0);
      if (!drawing) return;
      const char *utf8 = NSStringFromClass([drawing class]).UTF8String;
      if (utf8) *name = utf8;
    });
    jstring result = env->NewStringUTF(name->c_str());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_stride_app_glass_NativeGlass_nativeLayerTree(JNIEnv *env, jobject, jlong handle) {
    auto out = std::make_shared<std::string>();
    AskAppKitRunLoop("layer tree query", [out, handle] {
      auto found = gWindows.find(handle);
      if (found == gWindows.end()) return;
      NSView *content = found->second.contentView;
      if (content.layer) {
          DescribeLayer(content.layer, 0, *out);
          return;
      }
      for (NSView *view in content.subviews) {
          const char *name = NSStringFromClass([view class]).UTF8String;
          *out += std::string(name ? name : "?") + "\n";
          if (view.layer) DescribeLayer(view.layer, 1, *out);
      }
    });
    jstring result = env->NewStringUTF(out->c_str());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

// Test hooks. They reproduce the AWT wait shape: the AppKit thread sits in a nested run loop in
// AWT's mode (as doAWTRunLoopImpl does while AWT waits for the event thread), so any bridge
// entry that waits for the GCD main queue hangs. The test holds the loop until it releases it;
// the hold does not need a GCD hop to end.
static std::atomic<bool> gTestHoldActive{false};
static std::atomic<bool> gTestRelease{false};

extern "C" JNIEXPORT jboolean JNICALL
Java_stride_app_glass_NativeGlass_nativeTestHoldMainThread(JNIEnv *, jobject) {
    gTestRelease.store(false);
    gTestHoldActive.store(false);
    OnAppKitRunLoop([] {
      gTestHoldActive.store(true);
      while (!gTestRelease.load()) {
          @autoreleasepool {
              // AWT's own wait loops in this mode; the main dispatch queue is not serviced here.
              [[NSRunLoop currentRunLoop] runMode:kAwtRunLoopMode
                                       beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.005]];
          }
      }
      gTestHoldActive.store(false);
    });
    for (int attempt = 0; attempt < 400 && !gTestHoldActive.load(); attempt++) {
        usleep(5 * 1000);
    }
    return gTestHoldActive.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_stride_app_glass_NativeGlass_nativeTestReleaseMainThread(JNIEnv *, jobject) {
    gTestRelease.store(true);
    for (int attempt = 0; attempt < 400 && gTestHoldActive.load(); attempt++) {
        usleep(5 * 1000);
    }
}
