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
#include <cmath>
#include <cstdio>
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
long gNextHandle = 1;
DetachedState gDetached;

const double kWindowCornerRadius = 26.0;

JavaVM *gVm = nullptr;
jobject gAppearanceCallback = nullptr;
id gAppearanceObserver = nil;

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
    // Before AWT there is no application object and no main run loop to dispatch onto: report
    // unknown without touching AppKit.
    if (!NSApp) return 0;
    jint value = 0;
    RunOnMain([&] {
      value = CurrentAppearanceValue();
    });
    return value;
}

extern "C" JNIEXPORT jstring JNICALL
Java_stride_app_glass_NativeAppearance_nativeAppClassName(JNIEnv *env, jobject) {
    if (!NSApp) return env->NewStringUTF("");
    std::string name;
    RunOnMain([&] {
      if (NSApp) {
          const char *utf8 = NSStringFromClass([NSApp class]).UTF8String;
          if (utf8) name = utf8;
      }
    });
    jstring result = env->NewStringUTF(name.c_str());
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
    bool started = false;
    RunOnMain([&] {
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
      if (!callback) return;
      gAppearanceCallback = callback;
      gAppearanceObserver = [[NSDistributedNotificationCenter defaultCenter]
          addObserverForName:@"AppleInterfaceThemeChangedNotification"
                      object:nil
                       queue:[NSOperationQueue mainQueue]
                  usingBlock:^(NSNotification *) {
                    NotifyAppearance();
                  }];
      started = gAppearanceObserver != nil;
    });
    if (!started && callback && env) env->DeleteGlobalRef(callback);
    return started ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_stride_app_glass_NativeAppearance_nativeStopObserver(JNIEnv *, jobject) {
    RunOnMain([&] {
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
    if (!component) return 0;
    JAWTGetAWTProc getAWT = ResolveJAWT(env, javaHome);
    NSWindow *window = WindowForComponent(env, component, getAWT);
    if (!window) return 0;

    long handle = 0;
    RunOnMain([&] {
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
      handle = gNextHandle++;
      InstallDragMonitor(handle, state);
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

    jint applied = 0;
    RunOnMain([&] {
      auto located = gWindows.find(handle);
      if (located == gWindows.end()) return;
      WindowState &state = located->second;
      if (!state.container) return;
      SyncContainer(state);
      MakeDrawingChainTransparent(state);
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
    jint applied = 0;
    RunOnMain([&] {
      auto found = gWindows.find(handle);
      if (found == gWindows.end()) return;
      found->second.dragRects = rects;
      applied = (jint)rects.size();
    });
    return applied;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_stride_app_glass_NativeGlass_nativeHitTestDragRegion(JNIEnv *, jobject, jlong handle, jdouble x,
                                                          jdouble y) {
    bool hit = false;
    RunOnMain([&] {
      auto found = gWindows.find(handle);
      if (found == gWindows.end()) return;
      hit = DragRectsContain(found->second.dragRects, (CGFloat)x, (CGFloat)y);
    });
    return hit ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_stride_app_glass_NativeGlass_nativeDragMonitorCount(JNIEnv *, jobject) {
    jint count = 0;
    RunOnMain([&] {
      for (auto &entry : gWindows) {
          if (entry.second.dragMonitor) count++;
      }
    });
    return count;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_stride_app_glass_NativeGlass_nativeState(JNIEnv *env, jobject, jlong handle) {
    std::vector<double> values;
    RunOnMain([&] {
      auto found = gWindows.find(handle);
      NSInteger windowCount = (NSInteger)[[NSApp windows] count];
      if (found == gWindows.end()) {
          if (gDetached.handle == handle) {
              values = {
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
          values.push_back((double)ViewStyle(entry.second));
          values.push_back((double)(uint32_t)ViewTint(entry.second));
      }
      // The transparency chain of the Compose drawing layer: the value block is fixed-size so
      // the Java side can read it after the pieces.
      CALayer *drawing = FindDrawingLayerInView(state.contentView, 0);
      int ancestorCount = 0;
      bool ancestorsClear = drawing ? AncestorsAreTransparent(drawing, &ancestorCount) : false;
      NSColor *windowBackground = state.window.backgroundColor;
      values.push_back(drawing ? 1.0 : 0.0);
      values.push_back(drawing && drawing.isOpaque ? 1.0 : 0.0);
      values.push_back(drawing && drawing.backgroundColor
                               ? (double)CGColorGetAlpha(drawing.backgroundColor)
                               : 0.0);
      values.push_back(ancestorsClear ? 1.0 : 0.0);
      values.push_back((double)ancestorCount);
      values.push_back(windowBackground ? (double)windowBackground.alphaComponent : 0.0);
      values.push_back(ContainerIsBelowContent(state) ? 1.0 : 0.0);
      values.push_back(state.contentView.isOpaque ? 1.0 : 0.0);
      values.push_back(state.container && state.container.isOpaque ? 1.0 : 0.0);
      values.push_back(0.0);  // restored layers, only reported after detach
    });
    jdoubleArray result = env->NewDoubleArray((jsize)values.size());
    if (!result) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    if (!values.empty()) env->SetDoubleArrayRegion(result, 0, (jsize)values.size(), values.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
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
    if (!stringClass) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray((jsize)keys.size(), stringClass, nullptr);
    if (!result) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    for (jsize index = 0; index < (jsize)keys.size(); index++) {
        jstring value = env->NewStringUTF(keys[index].c_str());
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
    std::string name;
    RunOnMain([&] {
      auto found = gWindows.find(handle);
      if (found == gWindows.end()) return;
      CALayer *drawing = FindDrawingLayerInView(found->second.contentView, 0);
      if (!drawing) return;
      const char *utf8 = NSStringFromClass([drawing class]).UTF8String;
      if (utf8) name = utf8;
    });
    jstring result = env->NewStringUTF(name.c_str());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_stride_app_glass_NativeGlass_nativeLayerTree(JNIEnv *env, jobject, jlong handle) {
    std::string out;
    RunOnMain([&] {
      auto found = gWindows.find(handle);
      if (found == gWindows.end()) return;
      NSView *content = found->second.contentView;
      if (content.layer) {
          DescribeLayer(content.layer, 0, out);
          return;
      }
      for (NSView *view in content.subviews) {
          const char *name = NSStringFromClass([view class]).UTF8String;
          out += std::string(name ? name : "?") + "\n";
          if (view.layer) DescribeLayer(view.layer, 1, out);
      }
    });
    jstring result = env->NewStringUTF(out.c_str());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}
