/*
 * Headless Vulkan platform plugin -- drop-in replacement for libhybris's
 * own vulkanplatform_null.so.
 *
 * libhybris refuses to run any Vulkan call until it has dlopen'd
 * `vulkanplatform_<HYBRIS_VULKANPLATFORM>.so` (hybris/vulkan/ws.c
 * `_init_ws()`), and that variable defaults to "wayland". Upstream's
 * `vulkanplatform_null.so` is the headless answer, but it links three
 * libraries a compute-only client never touches:
 *
 *   - libwayland-server.so.0   (unconditional $(WAYLAND_SERVER_LIBS) in
 *                               vulkan/platforms/null/Makefile.am)
 *   - libgralloc.so.1          (-> libhardware/libui)
 *   - libhybris-vulkanplatformcommon.so.1
 *                              (-> libhybris-common, libsync,
 *                                  libwayland-client, libwayland-server)
 *
 * The Wayland ones are fatal in a container that ships no glibc Wayland:
 * the loader can't satisfy DT_NEEDED and `_init_ws()` assert()s, so
 * libhybris's libvulkan.so.1 dies before the app logs anything
 * (TAWC_DSH_DESIGN.md §11.1). vulkanplatformcommon contributes nothing
 * here -- it only stores the interface pointer that upstream's null
 * plugin never reads back.
 *
 * So this copy is the same pass-through with the links dropped: both
 * entry points forward straight to the Android vendor loader through the
 * proc address ws.c injects, and DT_NEEDED comes out as libc alone.
 *
 * `struct ws_module`'s layout is gated on WANT_WAYLAND (see ws.h), so
 * this file must be compiled against the same config.h libhybris itself
 * was built with -- scripts/build-libhybris.sh passes the libhybris build
 * dir on the include path for exactly that reason.
 * See notes/building.md "Headless Vulkan platform plugin".
 */

#include <ws.h>

static PFN_vkVoidFunction (*_vkGetInstanceProcAddr)(VkInstance instance, const char *pName) = NULL;

static VkResult (*_vkCreateInstance)(const VkInstanceCreateInfo *pCreateInfo,
                                     const VkAllocationCallbacks *pAllocator,
                                     VkInstance *pInstance) = NULL;

static VkResult (*_vkEnumerateInstanceExtensionProperties)(const char *pLayerName,
                                                           uint32_t *pPropertyCount,
                                                           VkExtensionProperties *pProperties) = NULL;

/* No WSI to initialise, so nothing to do. Upstream's null platform hands
 * the interface to vulkanplatformcommon here; ours has no consumer. */
static void nullws_init_module(struct ws_vulkan_interface *vulkan_iface)
{
    (void) vulkan_iface;
}

static VkResult nullws_vkEnumerateInstanceExtensionProperties(const char *pLayerName,
                                                             uint32_t *pPropertyCount,
                                                             VkExtensionProperties *pProperties)
{
    if (_vkEnumerateInstanceExtensionProperties == NULL) {
        _vkEnumerateInstanceExtensionProperties =
            (VkResult (*)(const char *, uint32_t *, VkExtensionProperties *))
                (*_vkGetInstanceProcAddr)(NULL, "vkEnumerateInstanceExtensionProperties");
    }
    return (*_vkEnumerateInstanceExtensionProperties)(pLayerName, pPropertyCount, pProperties);
}

static VkResult nullws_vkCreateInstance(const VkInstanceCreateInfo *pCreateInfo,
                                        const VkAllocationCallbacks *pAllocator,
                                        VkInstance *pInstance)
{
    if (_vkCreateInstance == NULL) {
        _vkCreateInstance =
            (VkResult (*)(const VkInstanceCreateInfo *, const VkAllocationCallbacks *, VkInstance *))
                (*_vkGetInstanceProcAddr)(NULL, "vkCreateInstance");
    }
    return (*_vkCreateInstance)(pCreateInfo, pAllocator, pInstance);
}

#ifdef WANT_WAYLAND
/* ws.c calls these three unconditionally when an app asks for them, so
 * they have to be real function pointers -- they just fail quietly. Same
 * behaviour as upstream's null platform. */
static VkResult nullws_vkCreateWaylandSurfaceKHR(VkInstance instance,
                                                 const VkWaylandSurfaceCreateInfoKHR *pCreateInfo,
                                                 const VkAllocationCallbacks *pAllocator,
                                                 VkSurfaceKHR *pSurface)
{
    (void) instance;
    (void) pCreateInfo;
    (void) pAllocator;
    (void) pSurface;
    return VK_ERROR_OUT_OF_HOST_MEMORY;
}

static VkBool32 nullws_vkGetPhysicalDeviceWaylandPresentationSupportKHR(VkPhysicalDevice physicalDevice,
                                                                       uint32_t queueFamilyIndex,
                                                                       struct wl_display *display)
{
    (void) physicalDevice;
    (void) queueFamilyIndex;
    (void) display;
    return VK_FALSE;
}

static void nullws_vkDestroySurfaceKHR(VkInstance instance,
                                       VkSurfaceKHR surface,
                                       const VkAllocationCallbacks *pAllocator)
{
    (void) instance;
    (void) surface;
    (void) pAllocator;
}
#endif

static void nullws_vkSetInstanceProcAddrFunc(PFN_vkVoidFunction addr)
{
    if (_vkGetInstanceProcAddr == NULL)
        _vkGetInstanceProcAddr = (PFN_vkVoidFunction (*)(VkInstance, const char *)) addr;
}

struct ws_module ws_module_info = {
    nullws_init_module,
    nullws_vkEnumerateInstanceExtensionProperties,
    nullws_vkCreateInstance,
#ifdef WANT_WAYLAND
    nullws_vkCreateWaylandSurfaceKHR,
    nullws_vkGetPhysicalDeviceWaylandPresentationSupportKHR,
    nullws_vkDestroySurfaceKHR,
    NULL, /* patchSurfaceCapabilities -- ws.c null-checks this one */
    NULL, /* prepareSwapchain        -- likewise */
#endif
    nullws_vkSetInstanceProcAddrFunc,
};
