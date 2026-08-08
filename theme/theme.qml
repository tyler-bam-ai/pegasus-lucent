import QtQuick 2.9
import QtMultimedia 5.9
import QtGraphicalEffects 1.0
import SortFilterProxyModel 0.2

FocusScope {
    id: root
    focus: true
    width: 1920
    height: 1080

    readonly property string lucentVersion: "3.2.15"

    property string page: "home"
    // 0 systems; 1 continue; 2 most played; 3 recently added;
    // 4 critic; 5 user; 6 A-Z; 7 release.
    property int homeZone: 0
    property bool previewReady: false
    property double previewRequestSequence: Date.now()
    property double currentBottomPreviewSequence: 0
    property var currentBottomPreviewGame: null
    property bool launchPollPending: false
    property bool applicationWasActive: false
    // Capability detection keeps physical lower-display playback exclusive to
    // dual-screen hardware. Single-screen devices use the in-theme PIP below.
    property bool dualScreenDevice: true
    property string previewPlacementMode: "auto" // auto, bottom, top, off
    property bool previewSoundEnabled: true
    // Single-screen devices can exchange the PIP and box-art columns. The
    // preference may persist across hardware, but is never applied or exposed
    // while a physical lower display is available.
    property bool singleScreenMediaSwapped: false
    // Cover View is a vertical sequence of Systems plus the seven library
    // shelves. One large row is the calm default; users can expose two or
    // three at once and can reorder the sequence without changing what each
    // shelf means.
    property int coverViewRowCount: 1
    property var coverRowOrder: [0, 1, 2, 3, 4, 5, 6, 7]
    property bool coverOrderEditorOpen: false
    property int coverOrderEditorIndex: 0
    readonly property real coverContentTop: 330
    readonly property real coverContentHeight: 710
    readonly property real coverRowSlotHeight: coverContentHeight / coverViewRowCount
    readonly property real coverShelfCardWidth: coverViewRowCount === 1 ? 330 :
                                                  (coverViewRowCount === 2 ? 238 : 196)
    // System identity is intentionally static while selected. Every system
    // owns one decoded wallpaper and receives its next random wallpaper only
    // after the user leaves it. The experimental motion layer was removed
    // because decoder startup could replace the visible artwork after entry.
    property bool systemMotionEnabled: false
    property bool systemVideoStarted: false
    property string currentSystemMotionSource: ""
    property bool settingsOpen: false
    property int settingsIndex: 0
    property bool rightStickViewSwitchingEnabled: true
    property bool viewTransitionsEnabled: false
    property bool liquidGlassEnabled: false
    // Platform family means the hardware brand (Nintendo, PlayStation, Sega,
    // Xbox/Microsoft, Atari, and so on).  It is more precise than the generic
    // word "company" and remains useful when many studios published games for
    // the same console.
    property string accentGrouping: "family" // family, system
    property var customFamilyAccents: ({})
    property var customSystemAccents: ({})
    property bool accentEditorOpen: false
    property int accentEditorTargetIndex: 0
    property int accentEditorChannel: 0 // target, hue, saturation, lightness
    property real accentEditorHue: 0
    property real accentEditorSaturation: 0.78
    property real accentEditorLightness: 0.58
    property bool systemLedEnabled: true
    property int systemLedBrightness: 2
    // Read only by the hidden pre-3.0.40 settings markup retained below.
    property bool systemLedUseDeviceBrightness: false
    property string startViewPreference: "cover"
    readonly property int baseSettingsOptionCount: dualScreenDevice ? 15 : 16
    // Lucent's unified build has one in-window engine policy. Standalone
    // emulator selection would break the one-app/one-window contract, so the
    // legacy per-system external controls are not part of the visible settings.
    readonly property int settingsOptionCount: baseSettingsOptionCount
    readonly property int settingsPageSize: 6
    readonly property int settingsPage: Math.floor(settingsIndex / settingsPageSize)
    property bool searchOpen: false
    property string searchQuery: ""
    property bool searchKeyboardAccepting: false
    property bool gameActionOpen: false
    property string gameActionMode: "menu"
    property int gameActionIndex: 0
    property var gameActionGame: null
    property string gameActionMessage: ""
    property var renamedGameTitles: ({})
    property var hiddenGameIds: ({})
    // These are deliberately category-specific. Removing a behavioral item
    // from Continue, Most Played, or Recently Added must never hide that game
    // from the complete critic/user/A-Z/release library views.
    property var removedHomeCategoryGameIds: ({ "1": {}, "2": {}, "3": {} })
    property int gameActionCategory: 0
    property int libraryMutationRevision: 0
    property int singleCurrentSlot: -1
    property int singleTargetSlot: -1
    property string singleSourceA: ""
    property string singleSourceB: ""
    property string singleSourceC: ""
    property var activePreviewSlot: null
    property var homePreviewGame: null
    property var departedHomeSlot: null
    property var lastHomeGameBySystem: ({})
    property var homePreviewCandidatesBySystem: ({})
    property int lastSystemPreviewIndex: -1
    property int departedSystemIndex: -1
    property string sortMode: "user"
    property string gameViewMode: "covers"
    property string homeViewMode: "covers"
    property int homeListCategory: 1
    property int homeListFocusColumn: 1 // 0 systems, 1 category/game list
    property var homeListEntries: []
    property var homeListCache: ({})
    property var availableBrandSlugs: []
    property var collectionFolderMap: ({})
    property var systemGameCache: ({})
    property var libraryGameMap: ({})
    property var activeSystemGames: []
    property bool libraryIndexReady: false
    property var pendingLibraryIndex: null
    property var pendingLibraryGameMap: ({})
    property int libraryIndexBuildPosition: 0
    property bool updatePromptOpen: false
    property bool aboutOpen: false
    property int updatePromptChoice: 0
    property bool updatePromptDismissed: false
    property string updateStatusMessage: ""
    // Navigation state is durable, but QSettings writes must never run inside
    // a controller input frame. These values are flushed after interaction
    // settles; launch() still commits synchronously before leaving Pegasus.
    property bool navigationPersistencePending: false
    property string importState: "idle"
    // A recreated Qt scene polls the companion's last completed status. Treat
    // that first completed response as a silent baseline so returning from a
    // game never resurrects an old "library update complete" notification.
    property bool importStatusInitialized: false
    property real importProgress: 0
    property string importMessage: ""
    property string importDetail: ""
    property int importCurrent: 0
    property int importTotal: 0
    property var importTitles: []
    property int importIdentified: 0
    property int importAdded: 0
    property bool importNeedsReload: false
    property bool importReloadRequested: false
    property bool importToastVisible: false
    property string lastImportFingerprint: ""
    property var hardwarePhotoBySystem: []
    property int lastHardwareSystemIndex: -1
    property int upperArtworkSlot: 0
    property int upperArtworkPendingSlot: -1
    property string upperArtworkTarget: ""
    property int boxArtworkSlot: 0
    property int boxArtworkPendingSlot: -1
    property string boxArtworkTarget: ""
    property string boxArtworkSourceA: ""
    property string boxArtworkSourceB: ""
    // Capture the system that owns the open game page. Some Android controller
    // key-up events can arrive while the home ListView is losing focus; using
    // its live currentIndex after that transition can open/display a stale
    // neighboring collection.
    property int activeSystemIndex: 0
    property bool allSystemsActive: false
    property int activeGameSystemIndex: {
        // ListModel contents are dynamic. Referencing count makes this binding
        // re-evaluate after startup discovery appends the detected systems.
        var modelDependency = systemModel.count
        return systemIndexForGame(activeGame)
    }
    property int shelfDisplaySystemIndex: page === "home" && homeZone > 0 ?
            activeGameSystemIndex : -1
    readonly property bool homeListUsesAllSystemsAccent: page === "home" &&
            homeViewMode === "list" && systemRail.currentIndex === 0 &&
            homeListFocusColumn === 0
    property int displaySystemIndex: {
        // The aggregate List View owns a neutral visual identity. Its preview
        // game must never leak Nintendo/Sega/Sony/etc. color into the header,
        // category strip, lighting, or selected list row.
        if (homeListUsesAllSystemsAccent)
            return 0
        if (page === "games" && allSystemsActive && activeGameSystemIndex > 0)
            return activeGameSystemIndex
        if (page === "games")
            return activeSystemIndex
        if (shelfDisplaySystemIndex > 0)
            return shelfDisplaySystemIndex
        return systemRail.currentIndex
    }
    // All Systems represents the installed library, not whichever random game
    // happens to be supplying its preview. Keep the installed-platform logo row
    // visible while the system column owns focus; once a game is locked, the
    // header can switch to that game's individual platform brand.
    property bool showAvailableBrandRow: page === "home" &&
            systemRail.currentIndex === 0 &&
            ((homeViewMode === "covers" && homeZone === 0) ||
             (homeViewMode === "list" && homeListFocusColumn === 0))
    // Keep the highlighted home system separate from the collection backing
    // gameSortModel. Rebinding that source on every home-screen arrow forced a
    // complete proxy-model rebuild and score sort for a list that was hidden.
    property var selectedCollection: systemRail.currentIndex === 0 ? null :
            collectionNamed(systemModel.get(systemRail.currentIndex).collectionName)
    property var activeCollection: null
    // The expensive aggregate library has four persistent native indexes.
    // Individual systems stay on one small dynamic proxy, avoiding both the
    // 5,000-row re-sort on input and an excessive matrix of startup models.
    property var activeGameSortModel: {
        if (searchQuery !== "")
            return allSystemsActive ? allGamesSearchSortModel :
                                      systemGameSearchSortModel
        if (!allSystemsActive)
            return libraryIndexReady ? null : systemGameSortModel
        if (sortMode === "critic") return allCriticSortModel
        if (sortMode === "user") return allUserSortModel
        if (sortMode === "release") return allReleaseSortModel
        return allAlphaSortModel
    }
    property var activeGameModel: searchQuery === "" && !allSystemsActive && libraryIndexReady ?
            activeSystemGames : activeGameSortModel
    property int activeGameCount: searchQuery === "" && !allSystemsActive && libraryIndexReady ?
            activeSystemGames.length : (activeGameSortModel ? activeGameSortModel.count : 0)
    property var activeGame: {
        if (page === "games" && activeGameCount > 0)
            return gameAtDisplayIndex(gameRail.currentIndex)
        if (page === "home" && homeViewMode === "list")
            return homeListGameAt(homeListRail.currentIndex)
        if (page === "home" && homeZone > 0)
            return homeShelfGame(homeZone)
        return homePreviewGame
    }
    property color accent: systemModel.get(displaySystemIndex).accent
    onAccentChanged: {
        if (systemLedEnabled) systemLedCommit.restart()
    }
    property string clockText: ""

    readonly property bool homeListBrowsingSystems: page === "home" &&
            homeViewMode === "list" && homeListFocusColumn === 0
    readonly property bool showSystemBackdrop: page === "home" &&
            ((homeViewMode === "covers" && homeZone === 0) ||
             homeListBrowsingSystems)
    readonly property string currentViewName: page === "games" ? "SYSTEM VIEW" :
            (homeViewMode === "list" ? "LIST VIEW" : "COVER VIEW")

    /*
     * Qt 5 has no native Liquid Glass material, so reproduce the optical
     * behavior in one GPU pass. The rounded-rectangle SDF identifies the
     * physical edge of the lens; the background is displaced along that
     * edge normal, lightly scattered in the body, split spectrally at the
     * rim, then lit from the upper-left. This keeps the center readable while
     * making the perimeter visibly refract instead of merely looking blurred.
     */
    property string liquidGlassFragmentShader:
        "varying highp vec2 qt_TexCoord0;\n" +
        "uniform lowp sampler2D source;\n" +
        "uniform highp vec2 glassSize;\n" +
        "uniform highp float cornerRadius;\n" +
        "uniform highp float edgeThickness;\n" +
        "uniform highp float distortionStrength;\n" +
        "uniform highp float scatterRadius;\n" +
        "uniform highp float samplePadding;\n" +
        "uniform lowp vec4 glassTint;\n" +
        "uniform lowp float qt_Opacity;\n" +
        "highp float roundedBox(highp vec2 p, highp vec2 b, highp float r) {\n" +
        "    highp vec2 q = abs(p) - (b - vec2(r));\n" +
        "    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n" +
        "}\n" +
        "void main() {\n" +
        "    highp vec2 uv = qt_TexCoord0;\n" +
        "    highp vec2 safeSize = max(glassSize, vec2(1.0));\n" +
        "    highp vec2 sampleExtent = safeSize + vec2(samplePadding * 2.0);\n" +
        "    highp vec2 texel = 1.0 / sampleExtent;\n" +
        "    highp vec2 baseUv = (uv * safeSize + vec2(samplePadding)) / sampleExtent;\n" +
        "    highp vec2 halfBox = safeSize * 0.5;\n" +
        "    highp vec2 p = uv * safeSize - halfBox;\n" +
        "    highp float d = roundedBox(p, halfBox, cornerRadius);\n" +
        "    highp float mask = 1.0 - smoothstep(-0.75, 0.75, d);\n" +
        "    highp float insideDistance = max(-d, 0.0);\n" +
        "    highp float edge = 1.0 - smoothstep(0.8, edgeThickness, insideDistance);\n" +
        "    highp vec2 dx = vec2(1.0, 0.0);\n" +
        "    highp vec2 dy = vec2(0.0, 1.0);\n" +
        "    highp vec2 grad = vec2(\n" +
        "        roundedBox(p + dx, halfBox, cornerRadius) - roundedBox(p - dx, halfBox, cornerRadius),\n" +
        "        roundedBox(p + dy, halfBox, cornerRadius) - roundedBox(p - dy, halfBox, cornerRadius));\n" +
        "    highp vec2 normal = normalize(grad + vec2(0.0001));\n" +
        "    highp float lensRipple = 0.78 + 0.22 * cos(insideDistance * 0.42);\n" +
        "    highp float bend = distortionStrength * edge * edge * lensRipple;\n" +
        "    highp vec2 lensUv = clamp(baseUv - normal * bend * texel, texel, vec2(1.0) - texel);\n" +
        "    highp vec2 sx = vec2(scatterRadius * texel.x, 0.0);\n" +
        "    highp vec2 sy = vec2(0.0, scatterRadius * texel.y);\n" +
        "    lowp vec4 sampleColor = texture2D(source, lensUv) * 0.24;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv + sx, texel, vec2(1.0) - texel)) * 0.11;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv - sx, texel, vec2(1.0) - texel)) * 0.11;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv + sy, texel, vec2(1.0) - texel)) * 0.11;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv - sy, texel, vec2(1.0) - texel)) * 0.11;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv + sx + sy, texel, vec2(1.0) - texel)) * 0.08;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv + sx - sy, texel, vec2(1.0) - texel)) * 0.08;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv - sx + sy, texel, vec2(1.0) - texel)) * 0.08;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv - sx - sy, texel, vec2(1.0) - texel)) * 0.08;\n" +
        "    lowp vec3 color = sampleColor.rgb;\n" +
        "    highp float chroma = 1.35 * edge;\n" +
        "    highp vec2 redUv = clamp(lensUv - normal * chroma * texel, texel, vec2(1.0) - texel);\n" +
        "    highp vec2 blueUv = clamp(lensUv + normal * chroma * texel, texel, vec2(1.0) - texel);\n" +
        "    color.r = mix(color.r, texture2D(source, redUv).r, edge * 0.30);\n" +
        "    color.b = mix(color.b, texture2D(source, blueUv).b, edge * 0.30);\n" +
        "    highp float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));\n" +
        "    highp float adaptiveDim = 0.40 * smoothstep(0.46, 0.86, luminance);\n" +
        "    color *= 1.0 - adaptiveDim;\n" +
        "    color = mix(color, glassTint.rgb, glassTint.a);\n" +
        "    highp vec2 lightDirection = normalize(vec2(-0.62, -0.78));\n" +
        "    highp float litRim = pow(max(dot(normal, lightDirection), 0.0), 3.0) * edge;\n" +
        "    highp float darkRim = pow(max(dot(normal, -lightDirection), 0.0), 2.0) * edge;\n" +
        "    highp float innerCaustic = smoothstep(0.8, 3.0, insideDistance) *\n" +
        "        (1.0 - smoothstep(3.0, 9.0, insideDistance));\n" +
        "    color += vec3(0.14, 0.16, 0.19) * litRim;\n" +
        "    color += vec3(0.045, 0.05, 0.06) * innerCaustic;\n" +
        "    color *= 1.0 - 0.17 * darkRim;\n" +
        "    gl_FragColor = vec4(color * mask, mask) * qt_Opacity;\n" +
        "}\n"

    function brandSlugForSystem(index) {
        if (index < 0 || index >= systemModel.count) return ""
        return String(systemModel.get(index).family || "")
    }

    function brandNameForSystem(index) {
        var brand = brandSlugForSystem(index)
        if (brand === "sega") return "SEGA"
        if (brand === "sony") return "SONY"
        if (brand === "microsoft") return "MICROSOFT"
        if (brand === "nintendo") return "NINTENDO"
        if (brand === "arcade") return "ARCADE"
        if (brand === "atari") return "ATARI"
        if (brand === "commodore") return "COMMODORE"
        if (brand === "snk") return "SNK"
        if (brand === "nec") return "NEC"
        if (brand === "bandai") return "BANDAI"
        if (brand === "mattel") return "MATTEL"
        if (brand === "magnavox") return "MAGNAVOX"
        if (brand === "coleco") return "COLECO"
        if (brand === "apple") return "APPLE"
        if (brand === "amstrad") return "AMSTRAD"
        if (brand === "sinclair") return "SINCLAIR"
        if (brand === "3do") return "3DO"
        return "MULTI-PUBLISHER"
    }

    function brandLogoForSystem(index) {
        var brand = brandSlugForSystem(index)
        if (brand === "arcade")
            return Qt.resolvedUrl("assets/logos-png/arcade.png")
        return brand ? Qt.resolvedUrl("assets/brands/" + brand + ".png") : ""
    }

    function defaultFamilyAccent(family) {
        // Spread unrelated platform families around the color wheel while
        // retaining Lucent's established colors for the four families on the
        // reference Thor library.
        var defaults = {
            "nintendo": "#ff9f43", "sega": "#4fd17f",
            "sony": "#a987ff", "microsoft": "#4aa3ff",
            "arcade": "#35d0e6", "atari": "#ff5964",
            "commodore": "#23c9b8", "snk": "#ffd166",
            "nec": "#ef6cff", "bandai": "#ff7d3a",
            "mattel": "#48cae4", "magnavox": "#c7f464",
            "coleco": "#f72585", "apple": "#a8b2c1",
            "amstrad": "#00b4d8", "sinclair": "#e63946",
            "3do": "#b8f2e6", "open": "#7bdff2"
        }
        return defaults[String(family)] || "#dce4f2"
    }

    function hueChannel(p, q, t) {
        var wrapped = t
        if (wrapped < 0) wrapped += 1
        if (wrapped > 1) wrapped -= 1
        if (wrapped < 1 / 6) return p + (q - p) * 6 * wrapped
        if (wrapped < 1 / 2) return q
        if (wrapped < 2 / 3) return p + (q - p) * (2 / 3 - wrapped) * 6
        return p
    }

    function hslHex(hue, saturation, lightness) {
        var red = lightness
        var green = lightness
        var blue = lightness
        if (saturation > 0) {
            var q = lightness < 0.5 ?
                    lightness * (1 + saturation) :
                    lightness + saturation - lightness * saturation
            var p = 2 * lightness - q
            red = hueChannel(p, q, hue + 1 / 3)
            green = hueChannel(p, q, hue)
            blue = hueChannel(p, q, hue - 1 / 3)
        }
        return "#" + colorByteHex(red) + colorByteHex(green) + colorByteHex(blue)
    }

    function defaultSystemAccent(index) {
        if (index <= 0 || systemModel.count <= 1) return "#dce4f2"
        // Evenly spaced HSL hues make seven installed systems resolve to a
        // ROYGBIV-like spectrum, while any other count still uses the full
        // wheel instead of clustering near one color family.
        var count = Math.max(1, systemModel.count - 1)
        var hue = ((index - 1) % count) / count
        // ListModel infers this role as a string from systemCatalog. Returning
        // the same type is essential: assigning a QColor here can silently
        // leave the original family color in place on Qt 5.
        return hslHex(hue, 0.82, 0.59)
    }

    function resolvedAccentForSystem(index) {
        if (index <= 0) return "#dce4f2"
        var system = systemModel.get(index)
        if (accentGrouping === "system") {
            var systemKey = String(system.folder)
            return customSystemAccents[systemKey] || defaultSystemAccent(index)
        }
        var familyKey = String(system.family || "open")
        return customFamilyAccents[familyKey] || defaultFamilyAccent(familyKey)
    }

    function applyAccentPalette() {
        for (var index = 0; index < systemModel.count; ++index)
            systemModel.setProperty(index, "accent", resolvedAccentForSystem(index))
        if (systemLedEnabled) systemLedCommit.restart()
    }

    function setAccentGrouping(value) {
        accentGrouping = value === "system" ? "system" : "family"
        api.memory.set("lucentAccentGrouping", accentGrouping)
        accentEditorTargetIndex = 0
        applyAccentPalette()
    }

    function accentTargets() {
        var targets = []
        var seen = ({})
        for (var index = 1; index < systemModel.count; ++index) {
            var value = accentGrouping === "system" ?
                    String(systemModel.get(index).folder) :
                    String(systemModel.get(index).family || "open")
            if (seen[value]) continue
            seen[value] = true
            targets.push(value)
        }
        return targets
    }

    function accentTargetLabel(key) {
        if (accentGrouping === "family") {
            for (var index = 1; index < systemModel.count; ++index) {
                if (String(systemModel.get(index).family || "open") === key)
                    return brandNameForSystem(index)
            }
            return String(key).toUpperCase()
        }
        for (var systemIndex = 1; systemIndex < systemModel.count; ++systemIndex) {
            if (String(systemModel.get(systemIndex).folder) === key)
                return String(systemModel.get(systemIndex).name)
        }
        return String(key).toUpperCase()
    }

    function accentEditorColor() {
        return Qt.hsla(accentEditorHue, accentEditorSaturation,
                accentEditorLightness, 1.0)
    }

    function loadAccentEditorTarget() {
        var targets = accentTargets()
        if (targets.length === 0) return
        accentEditorTargetIndex = Math.max(0,
                Math.min(targets.length - 1, accentEditorTargetIndex))
        var key = targets[accentEditorTargetIndex]
        var saved = accentGrouping === "system" ?
                customSystemAccents[key] : customFamilyAccents[key]
        var rawColor = saved || (accentGrouping === "system" ?
                resolvedAccentForSystem(accentEditorTargetIndex + 1) :
                defaultFamilyAccent(key))
        var color = Qt.tint(rawColor, "#00000000")
        accentEditorHue = color.hslHue < 0 ? 0 : color.hslHue
        accentEditorSaturation = color.hslSaturation
        accentEditorLightness = color.hslLightness
    }

    function openAccentEditor() {
        accentEditorTargetIndex = 0
        accentEditorChannel = 0
        loadAccentEditorTarget()
        accentEditorOpen = true
    }

    function storeAccentEditorColor() {
        var targets = accentTargets()
        if (targets.length === 0) return
        var key = targets[accentEditorTargetIndex]
        var hex = "#" + colorByteHex(accentEditorColor().r) +
                colorByteHex(accentEditorColor().g) +
                colorByteHex(accentEditorColor().b)
        if (accentGrouping === "system") {
            var systemCopy = JSON.parse(JSON.stringify(customSystemAccents))
            systemCopy[key] = hex
            customSystemAccents = systemCopy
            api.memory.set("lucentCustomSystemAccents", JSON.stringify(systemCopy))
        } else {
            var familyCopy = JSON.parse(JSON.stringify(customFamilyAccents))
            familyCopy[key] = hex
            customFamilyAccents = familyCopy
            api.memory.set("lucentCustomFamilyAccents", JSON.stringify(familyCopy))
        }
        applyAccentPalette()
    }

    function adjustAccentEditor(direction) {
        var targets = accentTargets()
        if (targets.length === 0) return
        if (accentEditorChannel === 0) {
            accentEditorTargetIndex = (accentEditorTargetIndex +
                    (direction < 0 ? -1 : 1) + targets.length) % targets.length
            loadAccentEditorTarget()
            return
        }
        if (accentEditorChannel === 1)
            accentEditorHue = (accentEditorHue + (direction < 0 ? -1 : 1) / 72 + 1) % 1
        else if (accentEditorChannel === 2)
            accentEditorSaturation = Math.max(0, Math.min(1,
                    accentEditorSaturation + (direction < 0 ? -0.02 : 0.02)))
        else
            accentEditorLightness = Math.max(0.18, Math.min(0.82,
                    accentEditorLightness + (direction < 0 ? -0.02 : 0.02)))
        storeAccentEditorColor()
    }

    function resetAccentEditorColor() {
        var targets = accentTargets()
        if (targets.length === 0) return
        var key = targets[accentEditorTargetIndex]
        if (accentGrouping === "system") {
            var systemCopy = JSON.parse(JSON.stringify(customSystemAccents))
            delete systemCopy[key]
            customSystemAccents = systemCopy
            api.memory.set("lucentCustomSystemAccents", JSON.stringify(systemCopy))
        } else {
            var familyCopy = JSON.parse(JSON.stringify(customFamilyAccents))
            delete familyCopy[key]
            customFamilyAccents = familyCopy
            api.memory.set("lucentCustomFamilyAccents", JSON.stringify(familyCopy))
        }
        applyAccentPalette()
        loadAccentEditorTarget()
    }

    function hardwareVariants(folder) {
        // Rotate every audited real-hardware angle available for the platform.
        // Only folders that physically contain fewer source photographs are
        // restricted; no generated stand-ins are used.
        if (folder === "all") return [0]
        if (folder === "psx") return [0, 1]
        if (folder === "nds") return [0, 1, 2, 3]
        if (folder === "arcade" || folder === "n64" ||
                folder === "ps2" || folder === "psp")
            return [0, 1, 2, 3, 4]
        return [0, 1, 2]
    }

    function hardwarePhotoUrl(index, variant) {
        var folder = systemModel.get(index).folder
        return Qt.resolvedUrl("assets/hardware-cutouts/" + folder + "/" + variant + ".png")
    }

    function initializeHardwarePhotos() {
        var photos = []
        for (var index = 0; index < systemModel.count; ++index) {
            var variants = hardwareVariants(systemModel.get(index).folder)
            photos[index] = hardwarePhotoUrl(index,
                    variants[Math.floor(Math.random() * variants.length)])
        }
        hardwarePhotoBySystem = photos
        lastHardwareSystemIndex = systemRail.currentIndex
    }

    function rerollHardwarePhoto(index) {
        if (index < 0 || index >= systemModel.count) return
        var variants = hardwareVariants(systemModel.get(index).folder)
        var current = String(hardwarePhotoBySystem[index] || "")
        var match = current.match(/\/(\d+)\.png(?:\?.*)?$/)
        var previous = match ? Number(match[1]) : -1
        var next = variants[Math.floor(Math.random() * variants.length)]
        if (variants.length > 1 && next === previous) {
            var position = variants.indexOf(next)
            next = variants[(position + 1 + Math.floor(Math.random() *
                    (variants.length - 1))) % variants.length]
        }
        var photos = hardwarePhotoBySystem.slice(0)
        photos[index] = hardwarePhotoUrl(index, next)
        hardwarePhotoBySystem = photos
    }

    function collectionNamed(name) {
        for (var i = 0; i < api.collections.count; ++i) {
            var candidate = api.collections.get(i)
            if (candidate.name === name)
                return candidate
        }
        return null
    }

    function rebuildVisibleSystems() {
        while (systemModel.count > 1)
            systemModel.remove(systemModel.count - 1)
        for (var index = 1; index < systemCatalog.count; ++index) {
            var definition = systemCatalog.get(index)
            var collection = collectionNamed(definition.collectionName)
            if (!collection || collection.games.count <= 0)
                continue
            systemModel.append({
                "name": definition.name,
                "years": definition.years,
                "mark": definition.mark,
                "collectionName": definition.collectionName,
                "folder": definition.folder,
                "family": definition.family,
                "accent": definition.accent
            })
        }
        applyAccentPalette()
        refreshAvailableBrands()
        Qt.callLater(function() { root.loadLibraryIndex() })
    }

    function refreshAvailableBrands() {
        var seen = ({})
        var brands = []
        var folders = ({})
        for (var index = 1; index < systemModel.count; ++index) {
            folders[String(systemModel.get(index).collectionName)] =
                    String(systemModel.get(index).folder)
            var brand = brandSlugForSystem(index)
            if (brand === "" || seen[brand]) continue
            seen[brand] = true
            brands.push(brand)
        }
        collectionFolderMap = folders
        availableBrandSlugs = brands
    }

    function libraryCacheKey(folder, title) {
        return String(folder || "") + "|" +
                String(title || "").toLowerCase().trim().replace(/\s+/g, " ")
    }

    function loadLibraryIndex() {
        var request = new XMLHttpRequest()
        request.onreadystatechange = function() {
            if (request.readyState !== XMLHttpRequest.DONE) return
            if (request.status !== 200) {
                libraryIndexRetry.restart()
                return
            }
            try {
                var payload = JSON.parse(request.responseText)
                // Keep the native service's compact key arrays intact. Turning
                // every key into a QML game object in one callback created a
                // multi-second UI-thread stall. Only the 5K game lookup is
                // built here, in small event-loop slices; individual rails
                // resolve the handful of keys they actually render.
                root.libraryIndexReady = false
                root.pendingLibraryIndex = payload.systems || ({})
                root.pendingLibraryGameMap = ({})
                root.libraryIndexBuildPosition = 0
                libraryIndexBuildTimer.restart()
            } catch (error) {
                libraryIndexRetry.restart()
            }
        }
        request.open("GET", "http://127.0.0.1:43821/library/index", true)
        request.send()
    }

    function continueLibraryIndexBuild() {
        if (!pendingLibraryIndex) return
        var gameMap = pendingLibraryGameMap
        var end = Math.min(api.allGames.count, libraryIndexBuildPosition + 180)
        for (var gameIndex = libraryIndexBuildPosition; gameIndex < end; ++gameIndex) {
            var game = api.allGames.get(gameIndex)
            if (!isLucentLibraryGame(game) || !gameVisibleAfterMutation(game)) continue
            for (var collectionIndex = 0;
                 collectionIndex < game.collections.count; ++collectionIndex) {
                var collectionName = String(game.collections.get(collectionIndex).name || "")
                var folder = collectionFolderMap[collectionName]
                if (!folder) continue
                gameMap[libraryCacheKey(folder, game.title)] = game
                break
            }
        }
        libraryIndexBuildPosition = end
        if (end < api.allGames.count) {
            libraryIndexBuildTimer.restart()
            return
        }

        libraryGameMap = gameMap
        systemGameCache = pendingLibraryIndex
        pendingLibraryIndex = null
        pendingLibraryGameMap = ({})
        libraryIndexReady = true
        homeListCache = ({})
        activateCachedSystemSort()
        if (page === "home" && homeViewMode === "list")
            rebuildHomeList()
        else if (page === "games") {
            gameRail.currentIndex = Math.max(0,
                    Math.min(gameRail.currentIndex, activeGameCount - 1))
            Qt.callLater(function() { root.activateGamePreview() })
        }
    }

    function activateCachedSystemSort() {
        if (allSystemsActive || activeSystemIndex <= 0) {
            activeSystemGames = []
            return
        }
        var folder = String(systemModel.get(activeSystemIndex).folder)
        var systemCache = systemGameCache[folder]
        activeSystemGames = systemCache && systemCache[sortMode] ?
                systemCache[sortMode] : []
    }

    function recentGame(index) {
        if (index < 0 || index >= recentModel.count)
            return null
        return api.allGames.get(recentModel.mapToSource(index))
    }

    function proxyGame(proxy, index) {
        if (!proxy || index < 0 || index >= proxy.count)
            return null
        return api.allGames.get(proxy.mapToSource(index))
    }

    function mostPlayedGame(index) {
        return proxyGame(mostPlayedModel, index)
    }

    function aggregateSortedSourceIndex(proxy, index) {
        if (!proxy || index < 0 || index >= proxy.count) return -1
        var filteredIndex = proxy.mapToSource(index)
        return filteredIndex < 0 ? -1 : allLibraryFilterModel.mapToSource(filteredIndex)
    }

    function aggregateSortedGame(proxy, index) {
        var sourceIndex = aggregateSortedSourceIndex(proxy, index)
        return sourceIndex < 0 ? null : api.allGames.get(sourceIndex)
    }

    function recentlyAddedGame(index) {
        if (index < 0 || index >= recentlyAddedModel.count)
            return null
        return api.allGames.get(recentlyAddedModel.get(index).sourceIndex)
    }

    function rebuildRecentlyAddedModel() {
        var candidates = []
        for (var index = 0; index < api.allGames.count; ++index) {
            var game = api.allGames.get(index)
            if (!isLucentLibraryGame(game) || !gameVisibleAfterMutation(game) ||
                    !gameVisibleInHomeCategory(game, 3))
                continue
            var values = game.extra ? game.extra["added-at"] : null
            var timestamp = Number(values || 0)
            if (timestamp > 0)
                candidates.push({ "sourceIndex": index, "timestamp": timestamp })
        }
        candidates.sort(function(left, right) { return right.timestamp - left.timestamp })
        recentlyAddedModel.clear()
        for (var candidate = 0; candidate < Math.min(60, candidates.length); ++candidate)
            recentlyAddedModel.append(candidates[candidate])
    }

    function homeShelfModel(zone) {
        if (zone === 1) return recentModel
        if (zone === 2) return mostPlayedModel
        if (zone === 3) return recentlyAddedModel
        if (zone === 4) return allCriticSortModel
        if (zone === 5) return allUserSortModel
        if (zone === 6) return allAlphaSortModel
        return allReleaseSortModel
    }

    function homeShelfRail(zone) {
        if (zone === 1) return recentRail
        if (zone === 2) return mostPlayedRail
        if (zone === 3) return recentlyAddedRail
        if (zone === 4) return criticRail
        if (zone === 5) return userRail
        if (zone === 6) return alphaRail
        return releaseRail
    }

    function homeShelfGameAt(zone, index) {
        if (zone === 1) return recentGame(index)
        if (zone === 2) return mostPlayedGame(index)
        if (zone === 3) return recentlyAddedGame(index)
        return aggregateSortedGame(homeShelfModel(zone), index)
    }

    function homeShelfGame(zone) {
        var rail = homeShelfRail(zone)
        return rail ? homeShelfGameAt(zone, rail.currentIndex) : null
    }

    function homeShelfName(zone) {
        if (zone === 1) return "CONTINUE PLAYING"
        if (zone === 2) return "MOST PLAYED"
        if (zone === 3) return "RECENTLY ADDED"
        if (zone === 4) return "CRITIC SCORE"
        if (zone === 5) return "USER SCORE"
        if (zone === 6) return "A–Z"
        return "RELEASE DATE"
    }

    function homeCategorySourceIndex(zone, index) {
        if (zone === 1)
            return index >= 0 && index < recentModel.count ? recentModel.mapToSource(index) : -1
        if (zone === 2)
            return index >= 0 && index < mostPlayedModel.count ? mostPlayedModel.mapToSource(index) : -1
        if (zone === 3)
            return index >= 0 && index < recentlyAddedModel.count ?
                    Number(recentlyAddedModel.get(index).sourceIndex) : -1
        return aggregateSortedSourceIndex(homeShelfModel(zone), index)
    }

    function homeCategoryCount(zone) {
        if (zone === 1) return recentModel.count
        if (zone === 2) return mostPlayedModel.count
        if (zone === 3) return recentlyAddedModel.count
        return homeShelfModel(zone).count
    }

    function homeListGameAt(index) {
        if (index < 0 || index >= homeListEntries.length)
            return null
        return homeListEntries[index]
    }

    function rebuildHomeList() {
        if (homeViewMode !== "list") return
        var systemIndex = Math.max(0, systemRail.currentIndex)
        var cacheKey = libraryMutationRevision + ":" + homeListCategory + ":" + systemIndex
        var cached = homeListCache[cacheKey]
        if (!cached) {
            cached = []
            if (systemIndex > 0 && homeListCategory >= 4) {
                var sortNames = ["", "", "", "", "critic", "user", "alpha", "release"]
                var folder = String(systemModel.get(systemIndex).folder)
                var systemCache = systemGameCache[folder]
                var orderedKeys = systemCache ? systemCache[sortNames[homeListCategory]] : null
                if (orderedKeys) {
                    for (var keyIndex = 0;
                         keyIndex < orderedKeys.length && cached.length < 240; ++keyIndex) {
                        var indexedGame = libraryGameMap[String(orderedKeys[keyIndex])]
                        if (indexedGame) cached.push(indexedGame)
                    }
                }
            } else {
                var sourceCount = homeCategoryCount(homeListCategory)
                // Aggregate sorted views stop after 240 rows. Continue/Most/
                // Recent are compact native models, so filtering those by one
                // system never traverses the full library.
                for (var index = 0; index < sourceCount && cached.length < 240; ++index) {
                    var game = homeShelfGameAt(homeListCategory, index)
                    if (game && (systemIndex === 0 || systemIndexForGame(game) === systemIndex))
                        cached.push(game)
                }
            }
            homeListCache[cacheKey] = cached
        }
        homeListEntries = cached.slice(0)
        homeZone = homeListCategory
        // While the user is moving through systems, no game row is selected.
        // A random item from the active category drives only the video preview;
        // the upper display stays on the selected console's hardware artwork.
        // Pressing A locks the system and deliberately selects row 0.
        homeListRail.currentIndex = homeListEntries.length > 0 ?
                (homeListFocusColumn === 0 ?
                 Math.floor(Math.random() * homeListEntries.length) : 0) : -1
        // Row zero is the deterministic A-button destination. Warm its cover
        // even while a random video is playing so entering the game column can
        // never expose that random video's old cover for a frame.
        boxArtworkPreloadEntry.source = boxArtwork(homeListGameAt(0))
        upperArtworkPreloadEntry.source = artwork(homeListGameAt(0))
        Qt.callLater(function() {
            if (root.homeViewMode !== "list" || root.page !== "home") return
            if (homeListRail.currentIndex >= 0)
                homeListRail.positionViewAtIndex(homeListRail.currentIndex, ListView.Center)
            root.activateHomeListPreview()
            root.forceActiveFocus()
        })
    }

    function cycleHomeListCategory(direction) {
        var next = homeListCategory + (direction < 0 ? -1 : 1)
        if (next < 1) {
            homeListFocusColumn = 0
            rebuildHomeList()
            root.forceActiveFocus()
            return
        }
        if (next > 7) return
        homeListFocusColumn = 1
        homeListCategory = next
        rebuildHomeList()
    }

    function enterHomeListGames() {
        homeListFocusColumn = 1
        if (homeListEntries.length > 0)
            homeListRail.currentIndex = 0
        activateHomeListPreview()
        root.forceActiveFocus()
    }

    function toggleHomeView() {
        homeViewMode = homeViewMode === "covers" ? "list" : "covers"
        api.memory.set("thoriumHomeView", homeViewMode)
        if (homeViewMode === "list") {
            homeListCategory = homeZone > 0 ? homeZone : 1
            homeListFocusColumn = 0
            rebuildHomeList()
        } else {
            homeZone = 0
            chooseSystemWallpaper(systemRail.currentIndex)
            Qt.callLater(function() { root.activateHomePreview(false) })
            systemRail.forceActiveFocus()
        }
    }

    function systemGameCount(index) {
        if (index === 0) return romGameCount()
        var collection = collectionAtSystem(index)
        return collection ? collection.games.count : 0
    }

    function systemIndexForGame(game) {
        if (!game || !game.collections || game.collections.count <= 0)
            return -1
        for (var collectionIndex = 0; collectionIndex < game.collections.count; ++collectionIndex) {
            var collectionName = game.collections.get(collectionIndex).name
            for (var systemIndex = 0; systemIndex < systemModel.count; ++systemIndex) {
                if (systemModel.get(systemIndex).collectionName === collectionName)
                    return systemIndex
            }
        }
        return -1
    }

    function packageDimensionsForSystem(index) {
        // Front-face dimensions in millimetres. Cover View uses one shared
        // pixels-per-millimetre scale, so unlike boxes retain the proportions
        // they would have on a physical shelf. Closely related retail formats
        // deliberately share a standard (CD jewel, DVD keep, Blu-ray, etc.).
        var folder = index > 0 && index < systemModel.count ?
                String(systemModel.get(index).folder) : ""
        var dimensions = {
            "nes": [127, 178], "snes": [180, 130], "n64": [180, 130],
            "gb": [125, 125], "gbc": [125, 125], "gba": [125, 125],
            "nds": [122, 135], "n3ds": [125, 135],
            "megadrive": [130, 180], "gamegear": [125, 180],
            "saturn": [142, 125], "dreamcast": [142, 125],
            "psx": [142, 125], "ps2": [135, 190], "ps3": [135, 172],
            "ps4": [135, 172], "ps5": [135, 172],
            "psp": [105, 177], "psvita": [105, 135],
            "gc": [135, 190], "wii": [135, 190], "wiiu": [135, 190],
            "switch": [105, 170], "windows": [135, 190],
            "xbox": [135, 190], "xbox360": [135, 190],
            "arcade": [135, 190]
        }
        var selected = dimensions[folder] || [135, 190]
        return { "width": selected[0], "height": selected[1] }
    }

    function packageDimensionsForGame(game) {
        return packageDimensionsForSystem(systemIndexForGame(game))
    }

    function coverShelfPixelsPerMillimetre() {
        if (coverViewRowCount === 1) return 2.45
        if (coverViewRowCount === 2) return 1.05
        return 0.56
    }

    function isLucentLibraryGame(game) {
        // Pegasus exposes installed Android applications through api.allGames.
        // Lucent is a ROM library: the built-in Android provider is never a
        // game source. Keep real ROM collections visible even before a custom
        // rail card/logo has been added for a newly detected platform.
        if (!game || !game.collections || game.collections.count <= 0)
            return false
        for (var index = 0; index < game.collections.count; ++index) {
            var name = String(game.collections.get(index).name || "").toLowerCase()
            if (name === "android" || name === "android apps" ||
                    name === "applications" || name === "apps")
                return false
        }
        return true
    }

    function romGameCount() {
        // Referencing count keeps this binding reactive when Pegasus reparses
        // metadata, while the identity test excludes Applications/emulators.
        var sourceCount = api.allGames.count
        var count = 0
        for (var index = 0; index < sourceCount; ++index) {
            if (isLucentLibraryGame(api.allGames.get(index))) ++count
        }
        return count
    }

    function markForGame(game) {
        var index = systemIndexForGame(game)
        return index >= 0 ? systemModel.get(index).mark : "GAME"
    }

    function accentForGame(game) {
        var index = systemIndexForGame(game)
        return index >= 0 ? systemModel.get(index).accent : root.accent
    }

    function homeListAccentForGame(game) {
        return homeListUsesAllSystemsAccent ? systemModel.get(0).accent :
                                              accentForGame(game)
    }

    function artwork(game) {
        if (!game) return ""
        // Never promote a capture or portrait cover into the wallpaper layer.
        // Missing real fanart falls through to the console backdrop underneath.
        return game.assets.background || ""
    }

    function chooseSystemWallpaper(index) {
        var wrapped = wrappedSystemIndex(index)
        if (wrapped < 0) return
        var departed = lastHardwareSystemIndex
        if (hardwarePhotoBySystem.length > 0 && departed >= 0 && departed !== wrapped)
            rerollHardwarePhoto(departed)
        lastHardwareSystemIndex = wrapped
    }

    function upperArtworkSource() {
        if (showSystemBackdrop)
            return ""
        if (!activeGame) return ""
        // Only provider-labeled/vision-audited background art belongs here.
        // The system backdrop remains visible when no genuine wallpaper exists.
        return activeGame.assets.background || ""
    }

    function upperArtworkLayer(slot) {
        return slot === 0 ? upperArtworkA : upperArtworkB
    }

    function promoteUpperArtwork(slot) {
        if (slot !== upperArtworkPendingSlot)
            return
        var layer = upperArtworkLayer(slot)
        if (!layer || layer.status !== Image.Ready ||
                String(layer.source) !== String(upperArtworkTarget))
            return
        upperArtworkSlot = slot
        upperArtworkPendingSlot = -1
    }

    function queueUpperArtwork(game, previousGame, nextGame) {
        // Decode both likely D-pad destinations before they are selected.
        // Assigning one of these URLs to the visible standby buffer then hits
        // Qt's image cache instead of briefly exposing the black base layer.
        upperArtworkPreloadPrevious.source = artwork(previousGame)
        upperArtworkPreloadNext.source = artwork(nextGame)

        var requested = String(artwork(game) || "")
        if (!requested) {
            // A missing wallpaper is a real state, not a slow load. Hide both
            // decoded buffers immediately so the neutral platform backdrop
            // underneath is revealed. Keeping the old slot visible here made
            // the previous game's artwork look as though it belonged to the
            // newly selected title.
            upperArtworkTarget = ""
            upperArtworkPendingSlot = -1
            upperArtworkSlot = -1
            return
        }
        var activeLayer = upperArtworkLayer(upperArtworkSlot)
        if (activeLayer && String(activeLayer.source) === requested) {
            // Rapidly moving away and straight back can leave a later request
            // decoding in the standby slot. Cancel its promotion so it cannot
            // replace the artwork the user has already returned to.
            upperArtworkTarget = requested
            upperArtworkPendingSlot = -1
            return
        }
        var incomingSlot = upperArtworkSlot === 0 ? 1 : 0
        var incomingLayer = upperArtworkLayer(incomingSlot)
        upperArtworkTarget = requested
        upperArtworkPendingSlot = incomingSlot
        incomingLayer.source = requested
        if (incomingLayer.status === Image.Ready)
            promoteUpperArtwork(incomingSlot)
    }

    function boxArtwork(game) {
        return game && game.assets ? String(game.assets.boxFront || "") : ""
    }

    function boxArtworkLayer(slot) {
        return slot === 0 ? homeListBoxArtA : homeListBoxArtB
    }

    function setBoxArtworkSource(slot, source) {
        if (slot === 0) boxArtworkSourceA = source
        else boxArtworkSourceB = source
    }

    function promoteBoxArtwork(slot) {
        if (slot !== boxArtworkPendingSlot)
            return
        var layer = boxArtworkLayer(slot)
        if (!layer || layer.status !== Image.Ready ||
                String(layer.source) !== String(boxArtworkTarget))
            return
        boxArtworkSlot = slot
        boxArtworkPendingSlot = -1
    }

    function queueBoxArtwork(game, previousGame, nextGame) {
        // Decode both directions before input reaches them. The visible cover
        // remains intact until the requested standby buffer is fully ready.
        boxArtworkPreloadPrevious.source = boxArtwork(previousGame)
        boxArtworkPreloadNext.source = boxArtwork(nextGame)

        var requested = boxArtwork(game)
        if (!requested) {
            boxArtworkTarget = ""
            boxArtworkPendingSlot = -1
            boxArtworkSlot = -1
            return
        }
        var activeSource = boxArtworkSlot === 0 ? boxArtworkSourceA : boxArtworkSourceB
        if (String(activeSource) === requested) {
            boxArtworkTarget = requested
            boxArtworkPendingSlot = -1
            return
        }
        var incomingSlot = boxArtworkSlot === 0 ? 1 : 0
        boxArtworkTarget = requested
        boxArtworkPendingSlot = incomingSlot
        setBoxArtworkSource(incomingSlot, requested)
        var incomingLayer = boxArtworkLayer(incomingSlot)
        if (incomingLayer && incomingLayer.status === Image.Ready)
            promoteBoxArtwork(incomingSlot)
    }

    function numericExtra(game, key) {
        if (!game || !game.extra) return NaN
        var raw = game.extra[key]
        // Pegasus preserves punctuation in x-* keys on this Android build.
        // Accept both API spellings so metadata refreshes cannot blank scores.
        if ((raw === undefined || raw === null || raw === "") && key === "userScore")
            raw = game.extra["user-score"] || game.extra["metacritic-user"]
        if ((raw === undefined || raw === null || raw === "") && key === "userComposite")
            raw = game.extra["user-composite"]
        if ((raw === undefined || raw === null || raw === "") && key === "criticComposite")
            raw = game.extra["critic-composite"]
        var value = Number(raw)
        return isNaN(value) ? NaN : value
    }

    function userScore(game) {
        // `rating` is reserved as the native, fast critic-sort role. Keep the
        // displayed user score sourced from its explicit metadata field.
        var score = numericExtra(game, "userComposite")
        if (isNaN(score)) score = numericExtra(game, "userScore")
        return isNaN(score) || score <= 0 ? NaN : score
    }

    function criticScore(game) {
        var score = numericExtra(game, "criticComposite")
        if (isNaN(score)) {
            score = game && game.extra ? Number(game.extra.critic) : NaN
            if (!isNaN(score) && score > 10) score /= 10.0
        }
        return isNaN(score) || score <= 0 ? NaN : score
    }

    function criticLabel(game) {
        return "CRITICS"
    }

    function scoreText(game) {
        var critic = criticScore(game)
        var user = userScore(game)
        return "CRITICS  " + (isNaN(critic) ? "N/A" : critic.toFixed(1)) +
                "     USERS  " + (isNaN(user) ? "N/A" : user.toFixed(1))
    }

    function releaseYear(game) {
        if (!game || !game.release) return "N/A"
        var formatted = Qt.formatDate(game.release, "yyyy")
        if (formatted && formatted !== "0" && formatted !== "NaN")
            return formatted
        var match = String(game.release).match(/(?:19|20)\d{2}/)
        return match ? match[0] : "N/A"
    }

    function gameFactsText(game) {
        return scoreText(game) + "     RELEASE  " + releaseYear(game)
    }

    function gameAtDisplayIndex(index) {
        if ((!allSystemsActive && !activeCollection) || activeGameCount <= 0 ||
                index < 0 || index >= activeGameCount)
            return null
        if (!allSystemsActive && searchQuery === "" && libraryIndexReady)
            return libraryGameMap[String(activeSystemGames[index])] || null
        if (allSystemsActive) {
            var allSourceIndex = activeGameSortModel.mapToSource(index)
            // Normal aggregate browsing sorts the already-filtered base index;
            // search proxies still map directly to api.allGames.
            if (searchQuery === "")
                allSourceIndex = allLibraryFilterModel.mapToSource(allSourceIndex)
            return api.allGames.get(allSourceIndex)
        }
        return activeCollection.games.get(activeGameSortModel.mapToSource(index))
    }

    function cycleSort(direction) {
        var modes = ["critic", "user", "alpha", "release"]
        var current = modes.indexOf(sortMode)
        var step = direction === -1 ? -1 : 1
        sortMode = modes[(current + step + modes.length) % modes.length]
        activateCachedSystemSort()
        scheduleNavigationPersistence()
        if (page === "home" && homeViewMode === "list") {
            rebuildHomeList()
            root.forceActiveFocus()
            return
        }
        gameRail.currentIndex = 0
        // A model pointer swap destroys the previously focused delegate. Keep
        // the scope itself focused so consecutive shoulder presses are never
        // swallowed while the new delegate is instantiated.
        root.forceActiveFocus()
        // The selected warm proxy changes immediately. Preview/media work is
        // still coalesced outside this input frame.
        sortChangeCommit.restart()
    }

    function toggleGameView() {
        gameViewMode = gameViewMode === "covers" ? "list" : "covers"
        scheduleNavigationPersistence()
        Qt.callLater(function() {
            if (gameViewMode === "list")
                positionGameListAtIndex(gameRail.currentIndex)
            else
                gameRail.positionViewAtIndex(gameRail.currentIndex, ListView.Center)
        })
    }

    function positionGameListAtIndex(index) {
        var visibleRows = 8
        var leadingRows = 3
        var maximumStart = Math.max(0, activeGameCount - visibleRows)
        var start = Math.max(0, Math.min(maximumStart, index - leadingRows))
        gameListRail.positionViewAtIndex(start, ListView.Beginning)
    }

    function sortLabel(mode) {
        if (mode === "user") return "USER SCORE"
        if (mode === "critic") return "CRITIC SCORE"
        if (mode === "release") return "RELEASE"
        return "A–Z"
    }

    function videoSource(game) {
        return game && game.assets.video ? game.assets.video : ""
    }

    function gameIdentifier(game) {
        if (!game || !game.extra) return ""
        return String(game.extra["lucent-id"] || game.extra.lucentId || "")
    }

    // Older, hand-authored Pegasus metafiles predate Lucent's x-lucent-id.
    // They are still first-class library games, so behavioral preferences
    // need a durable local key. Destructive companion APIs continue to use
    // only the importer-issued identity above.
    function behavioralGameIdentifier(game) {
        var identity = gameIdentifier(game)
        if (identity !== "") return identity
        if (!game) return ""
        var title = String(game.title || "").trim().toLowerCase()
        if (title === "") return ""
        return "legacy:" + systemIndexForGame(game) + ":" + title
    }

    function displayTitle(game) {
        if (!game) return ""
        var identity = gameIdentifier(game)
        return identity !== "" && renamedGameTitles[identity] ?
                    renamedGameTitles[identity] : String(game.title || "")
    }

    function gameVisibleAfterMutation(game) {
        var revisionDependency = libraryMutationRevision
        var identity = gameIdentifier(game)
        return identity === "" || !hiddenGameIds[identity]
    }

    function gameVisibleInHomeCategory(game, category) {
        var revisionDependency = libraryMutationRevision
        if (category < 1 || category > 3) return true
        var identity = behavioralGameIdentifier(game)
        if (identity === "") return true
        var bucket = removedHomeCategoryGameIds[String(category)] || ({})
        return !bucket[identity]
    }

    function gameActionAllowsRemoveFromList() {
        return gameActionCategory >= 1 && gameActionCategory <= 3
    }

    function gameActionOptionCount() {
        return gameActionAllowsRemoveFromList() ? 3 : 2
    }

    function moveGameActionSelection(direction) {
        var count = gameActionOptionCount()
        gameActionIndex = (gameActionIndex + direction + count) % count
    }

    function openGameActions(game, index) {
        if (!game) return
        gameRail.currentIndex = index
        gameActionGame = game
        gameActionCategory = page === "home" ?
                    (homeViewMode === "list" ? homeListCategory : homeZone) : 0
        gameActionIndex = 0
        gameActionMode = "menu"
        gameActionMessage = ""
        gameActionOpen = true
        root.forceActiveFocus()
    }

    function closeGameActions() {
        Qt.inputMethod.reset()
        Qt.inputMethod.hide()
        renameField.focus = false
        gameActionOpen = false
        gameActionGame = null
        gameActionCategory = 0
        gameActionMode = "menu"
        root.forceActiveFocus()
    }

    function beginRenameGame() {
        if (!gameActionGame || gameIdentifier(gameActionGame) === "") {
            gameActionMessage = "This game must be imported by Lucent before it can be renamed."
            gameActionMode = "error"
            return
        }
        gameActionMode = "rename"
        renameField.text = displayTitle(gameActionGame)
        Qt.callLater(function() {
            renameField.forceActiveFocus()
            renameField.selectAll()
            Qt.inputMethod.show()
        })
    }

    function submitRenameGame() {
        var title = String(renameField.text || "").trim()
        var identity = gameIdentifier(gameActionGame)
        if (identity === "" || title === "") return
        Qt.inputMethod.hide()
        renameField.focus = false
        gameActionMode = "working"
        gameActionMessage = "RENAMING…"
        var request = new XMLHttpRequest()
        request.onreadystatechange = function() {
            if (request.readyState !== XMLHttpRequest.DONE) return
            if (request.status === 200) {
                root.renamedGameTitles[identity] = title
                root.libraryMutationRevision += 1
                root.libraryIndexReady = false
                root.loadLibraryIndex()
                root.gameActionMessage = "RENAMED"
                root.gameActionMode = "success"
            } else {
                root.gameActionMessage = "RENAME FAILED"
                root.gameActionMode = "error"
            }
        }
        request.open("GET", "http://127.0.0.1:43821/game/rename?id=" +
                     encodeURIComponent(identity) + "&title=" + encodeURIComponent(title), true)
        request.send()
    }

    function submitDeleteGame() {
        var identity = gameIdentifier(gameActionGame)
        if (identity === "") {
            gameActionMessage = "This game must be imported by Lucent before it can be deleted."
            gameActionMode = "error"
            return
        }
        gameActionMode = "working"
        gameActionMessage = "DELETING ROM FILE…"
        var request = new XMLHttpRequest()
        request.onreadystatechange = function() {
            if (request.readyState !== XMLHttpRequest.DONE) return
            if (request.status === 200) {
                root.hiddenGameIds[identity] = true
                root.libraryMutationRevision += 1
                root.libraryIndexReady = false
                root.loadLibraryIndex()
                root.gameActionMessage = "ROM FILE DELETED"
                root.gameActionMode = "success"
                gameRail.currentIndex = Math.max(0, Math.min(gameRail.currentIndex,
                                                             activeGameCount - 1))
            } else {
                root.gameActionMessage = "DELETE FAILED"
                root.gameActionMode = "error"
            }
        }
        request.open("GET", "http://127.0.0.1:43821/game/delete?id=" +
                     encodeURIComponent(identity), true)
        request.send()
    }

    function submitRemoveFromList() {
        if (!gameActionAllowsRemoveFromList()) {
            gameActionMessage = "REMOVE FROM LIST IS ONLY AVAILABLE FOR CONTINUE, MOST PLAYED, AND RECENTLY ADDED."
            gameActionMode = "error"
            return
        }
        var identity = behavioralGameIdentifier(gameActionGame)
        if (identity === "") {
            gameActionMessage = "THIS GAME COULD NOT BE IDENTIFIED"
            gameActionMode = "error"
            return
        }
        var allBuckets = removedHomeCategoryGameIds
        var categoryKey = String(gameActionCategory)
        var bucket = allBuckets[categoryKey] || ({})
        bucket[identity] = true
        allBuckets[categoryKey] = bucket
        removedHomeCategoryGameIds = allBuckets
        api.memory.set("lucentRemovedHomeCategoryGameIds", JSON.stringify(allBuckets))
        libraryMutationRevision += 1
        homeListCache = ({})
        if (gameActionCategory === 3)
            rebuildRecentlyAddedModel()
        if (page === "home" && homeViewMode === "list")
            rebuildHomeList()
        gameActionMessage = "REMOVED FROM " + homeShelfName(gameActionCategory)
        gameActionMode = "success"
    }

    function boxAspectForSystem(index) {
        // Width / height for the physical packaging used by each platform.
        // Games within one system always share the same visual canvas.
        var folder = systemModel.get(index).folder
        if (folder === "n64" || folder === "snes") return 1.43
        if (folder === "psx" || folder === "dreamcast") return 1.0
        if (folder === "nds") return 0.86
        if (folder === "ps3") return 0.79
        if (folder === "psp") return 0.58
        if (folder === "gb" || folder === "gba" || folder === "gbc") return 1.0
        return 0.70
    }

    function previewUrl(game, previousGame, nextGame, auxiliaryGame) {
        previewRequestSequence += 1
        currentBottomPreviewSequence = previewRequestSequence
        var video = videoSource(game)
        var art = game ? artwork(game) : ""
        var title = game ? displayTitle(game) : ""
        var gameSystemIndex = systemIndexForGame(game)
        var systemName = gameSystemIndex >= 0 ? systemModel.get(gameSystemIndex).name : ""
        var score = game ? scoreText(game) : ""
        return "http://127.0.0.1:43821/play?seq=" + previewRequestSequence +
                "&video=" + encodeURIComponent(video) +
                "&art=" + encodeURIComponent(art) +
                "&title=" + encodeURIComponent(title) +
                "&system=" + encodeURIComponent(systemName) +
                "&score=" + encodeURIComponent(score) +
                "&advance=" + (randomHomePreviewActive() ? "1" : "0") +
                "&preload_prev=" + encodeURIComponent(videoSource(previousGame)) +
                "&preload_next=" + encodeURIComponent(videoSource(nextGame)) +
                "&preload_aux=" + encodeURIComponent(videoSource(auxiliaryGame))
    }

    function sendBottomPreview(game, previousGame, nextGame, auxiliaryGame) {
        if (previewPlacementMode === "off") {
            currentBottomPreviewGame = null
            currentBottomPreviewSequence = 0
            singleCurrentSlot = -1
            requestPreviewEndpoint("blank")
            return
        }
        if (!useBottomPreview()) {
            currentBottomPreviewGame = null
            currentBottomPreviewSequence = 0
            requestPreviewEndpoint("blank")
            setSingleScreenPreview(videoSource(game), videoSource(previousGame),
                                   videoSource(nextGame))
            return
        }
        currentBottomPreviewGame = game
        var url = previewUrl(game, previousGame, nextGame, auxiliaryGame)
        var request = new XMLHttpRequest()
        request.open("GET", url, true)
        request.send()
    }

    function randomHomePreviewActive() {
        return page === "home" &&
                ((homeViewMode === "covers" && homeZone === 0) ||
                 (homeViewMode === "list" && homeListFocusColumn === 0))
    }

    function advanceRandomHomePreview() {
        if (page !== "home") return
        if (homeViewMode === "list" && homeListFocusColumn === 0) {
            if (homeListEntries.length <= 1) {
                activateHomeListPreview()
                return
            }
            var previous = homeListRail.currentIndex
            var next = Math.floor(Math.random() * (homeListEntries.length - 1))
            if (next >= previous) ++next
            homeListRail.currentIndex = next
            activateHomeListPreview()
        } else if (homeViewMode === "covers" && homeZone === 0) {
            activateHomePreview(true)
        }
    }

    function useBottomPreview() {
        if (!dualScreenDevice) return false
        return previewPlacementMode === "auto" || previewPlacementMode === "bottom"
    }

    function requestPreviewEndpoint(endpoint) {
        var request = new XMLHttpRequest()
        request.open("GET", "http://127.0.0.1:43821/" + endpoint, true)
        request.send()
    }

    function pollBottomLaunchRequest() {
        if (launchPollPending || !useBottomPreview() || !currentBottomPreviewGame)
            return
        launchPollPending = true
        var request = new XMLHttpRequest()
        request.onreadystatechange = function() {
            if (request.readyState !== XMLHttpRequest.DONE)
                return
            root.launchPollPending = false
            if (request.status !== 200)
                return
            try {
                var payload = JSON.parse(request.responseText)
                var requestedSequence = Number(payload.seq || 0)
                if (requestedSequence > 0 &&
                        requestedSequence === root.currentBottomPreviewSequence &&
                        root.currentBottomPreviewGame)
                    root.launch(root.currentBottomPreviewGame)
                var completedSequence = Number(payload.completedSeq || 0)
                if (completedSequence > 0 &&
                        completedSequence === root.currentBottomPreviewSequence &&
                        root.randomHomePreviewActive())
                    Qt.callLater(function() { root.advanceRandomHomePreview() })
            } catch (error) {
                // A missing optional companion must never affect navigation.
            }
        }
        request.open("GET", "http://127.0.0.1:43821/launch/status", true)
        request.send()
    }

    function refreshCurrentPreview() {
        if (previewPlacementMode === "off") {
            singleCurrentSlot = -1
            currentBottomPreviewGame = null
            currentBottomPreviewSequence = 0
            requestPreviewEndpoint("blank")
            return
        }
        if (!useBottomPreview())
            requestPreviewEndpoint("blank")
        else
            singleCurrentSlot = -1
        if (page === "home" && homeViewMode === "list")
            activateHomeListPreview()
        else if (page === "home" && homeZone === 0)
            activateHomePreview(false)
        else if (page === "games")
            activateGamePreview()
        else
            activateShelfPreview(homeZone)
    }

    function cyclePreviewPlacement(direction) {
        var modes = dualScreenDevice ? ["auto", "bottom", "top", "off"] :
                                       ["auto", "top", "off"]
        var current = modes.indexOf(previewPlacementMode)
        if (current < 0) current = 0
        previewPlacementMode = modes[(current + direction + modes.length) % modes.length]
        api.memory.set("thoriumPreviewPlacement", previewPlacementMode)
        refreshCurrentPreview()
    }

    function setPreviewSoundEnabled(enabled) {
        previewSoundEnabled = Boolean(enabled)
        api.memory.set("thoriumPreviewSound", previewSoundEnabled)
        requestPreviewEndpoint("settings/sound?enabled=" +
                (previewSoundEnabled ? "1" : "0"))
    }

    function setSingleScreenMediaSwapped(swapped) {
        if (dualScreenDevice) return
        singleScreenMediaSwapped = Boolean(swapped)
        api.memory.set("lucentSingleScreenMediaSwapped", singleScreenMediaSwapped)
    }

    function validatedCoverRowOrder(value) {
        var candidate = value
        if (typeof value === "string") {
            try { candidate = JSON.parse(value) } catch (error) { candidate = [] }
        }
        if (!candidate || candidate.length === undefined) candidate = []
        var output = []
        for (var index = 0; index < candidate.length; ++index) {
            var zone = Number(candidate[index])
            if (zone >= 0 && zone <= 7 && output.indexOf(zone) < 0)
                output.push(zone)
        }
        for (var missing = 0; missing <= 7; ++missing) {
            if (output.indexOf(missing) < 0) output.push(missing)
        }
        return output
    }

    function setCoverViewRowCount(value) {
        coverViewRowCount = Math.max(1, Math.min(3, Math.round(Number(value))))
        api.memory.set("lucentCoverViewRowCount", coverViewRowCount)
    }

    function coverZonePosition(zone) {
        var position = coverRowOrder.indexOf(Number(zone))
        return position < 0 ? Number(zone) : position
    }

    function coverWindowStart() {
        var rows = Math.max(1, Math.min(3, coverViewRowCount))
        var position = coverZonePosition(homeZone)
        var centered = position - Math.floor((rows - 1) / 2)
        return Math.max(0, Math.min(8 - rows, centered))
    }

    function coverZoneVisible(zone) {
        if (page !== "home" || homeViewMode !== "covers") return false
        var position = coverZonePosition(zone)
        var start = coverWindowStart()
        return position >= start && position < start + coverViewRowCount
    }

    function stepCoverZone(direction) {
        var position = coverZonePosition(homeZone)
        var next = Math.max(0, Math.min(coverRowOrder.length - 1,
                                       position + (direction < 0 ? -1 : 1)))
        focusHomeZone(Number(coverRowOrder[next]))
    }

    function coverRowName(zone) {
        if (zone === 0) return "SYSTEMS"
        return homeShelfName(zone)
    }

    function coverVerticalNavigationText() {
        var position = coverZonePosition(homeZone)
        var directions = []
        if (position > 0)
            directions.push("UP  ↑  " + coverRowName(Number(coverRowOrder[position - 1])))
        if (position < coverRowOrder.length - 1)
            directions.push("DOWN  ↓  " + coverRowName(Number(coverRowOrder[position + 1])))
        return directions.join("      •      ")
    }

    function openCoverOrderEditor() {
        coverOrderEditorIndex = Math.max(0, coverZonePosition(homeZone))
        coverOrderEditorOpen = true
        settingsOpen = false
    }

    function moveCoverOrderItem(direction) {
        var target = Math.max(0, Math.min(coverRowOrder.length - 1,
                                         coverOrderEditorIndex + direction))
        if (target === coverOrderEditorIndex) return
        var updated = coverRowOrder.slice(0)
        var held = updated[coverOrderEditorIndex]
        updated[coverOrderEditorIndex] = updated[target]
        updated[target] = held
        coverRowOrder = updated
        coverOrderEditorIndex = target
        api.memory.set("lucentCoverRowOrder", JSON.stringify(coverRowOrder))
    }

    function previewPlacementLabel() {
        if (previewPlacementMode === "bottom") return "LOWER DISPLAY"
        if (previewPlacementMode === "top") return "TOP-RIGHT PIP"
        if (previewPlacementMode === "off") return "OFF"
        return dualScreenDevice ? "AUTOMATIC (LOWER)" : "AUTOMATIC (PIP)"
    }

    function pollUpdateStatus() {
        var request = new XMLHttpRequest()
        request.onreadystatechange = function() {
            if (request.readyState !== XMLHttpRequest.DONE || request.status !== 200)
                return
            try {
                var payload = JSON.parse(request.responseText)
                root.updateStatusMessage = String(payload.message || "")
                if (Boolean(payload.installReady) && !root.updatePromptDismissed) {
                    root.updatePromptChoice = 0
                    root.updatePromptOpen = true
                    root.forceActiveFocus()
                }
            } catch (error) {
                // The updater is optional while an older package is migrating.
            }
        }
        request.open("GET", "http://127.0.0.1:43821/update/status", true)
        request.send()
    }

    function installReadyUpdate() {
        updatePromptOpen = false
        requestPreviewEndpoint("update/install")
    }

    function dismissReadyUpdate() {
        updatePromptDismissed = true
        updatePromptOpen = false
        root.forceActiveFocus()
    }

    function colorByteHex(value) {
        var text = Math.max(0, Math.min(255, Math.round(value * 255))).toString(16)
        return text.length < 2 ? "0" + text : text
    }

    function selectedLedColor() {
        return colorByteHex(accent.r) + colorByteHex(accent.g) + colorByteHex(accent.b)
    }

    function applySystemLedColor() {
        if (!systemLedEnabled) return
        var hardwareBrightness = Math.round(255 * systemLedBrightness / 100)
        requestPreviewEndpoint("led?enabled=1&brightness=" +
                hardwareBrightness +
                "&color=" + selectedLedColor())
    }

    function setSystemLedEnabled(enabled) {
        systemLedEnabled = Boolean(enabled)
        api.memory.set("lucentSystemLedEnabled", systemLedEnabled)
        if (systemLedEnabled)
            systemLedCommit.restart()
        else
            requestPreviewEndpoint("led?enabled=0")
    }

    function setSystemLedBrightness(value) {
        var clamped = Math.max(1, Math.min(100, Number(value)))
        systemLedBrightness = Math.round(clamped)
        api.memory.set("lucentSystemLedBrightness", systemLedBrightness)
        if (systemLedEnabled)
            systemLedCommit.restart()
    }

    function setSystemLedUseDeviceBrightness(enabled) {
        // Compatibility target for the inert legacy row. The active UI always
        // uses the explicit one-percent-through-100-percent control.
        systemLedUseDeviceBrightness = false
        if (enabled) setSystemLedBrightness(1)
    }

    function startViewOptions() {
        var options = ["cover", "list", "all"]
        for (var index = 1; index < systemModel.count; ++index)
            options.push("system:" + String(systemModel.get(index).folder))
        return options
    }

    function startViewLabel(value) {
        if (value === "cover") return "COVER VIEW"
        if (value === "list") return "LIST VIEW"
        if (value === "all") return "ALL SYSTEMS VIEW"
        if (String(value).indexOf("system:") === 0) {
            var folder = String(value).substring(7)
            for (var index = 1; index < systemModel.count; ++index) {
                if (String(systemModel.get(index).folder) === folder)
                    return String(systemModel.get(index).name) + " VIEW"
            }
        }
        return "COVER VIEW"
    }

    function cycleStartView(direction) {
        var options = startViewOptions()
        var current = options.indexOf(startViewPreference)
        if (current < 0) current = 0
        var step = direction < 0 ? -1 : 1
        startViewPreference = options[(current + step + options.length) % options.length]
        api.memory.set("lucentStartView", startViewPreference)
    }

    function configuredStartSystemIndex() {
        if (String(startViewPreference).indexOf("system:") !== 0) return 0
        var folder = String(startViewPreference).substring(7)
        for (var index = 1; index < systemModel.count; ++index) {
            if (String(systemModel.get(index).folder) === folder) return index
        }
        return 0
    }

    function applyConfiguredStartView() {
        if (startViewPreference === "list") {
            page = "home"
            homeViewMode = "list"
            homeZone = 1
            homeListCategory = 1
            homeListFocusColumn = 0
            systemRail.currentIndex = 0
            return
        }
        if (startViewPreference === "all" ||
                String(startViewPreference).indexOf("system:") === 0) {
            activeSystemIndex = configuredStartSystemIndex()
            allSystemsActive = activeSystemIndex === 0
            activeCollection = allSystemsActive ? null : collectionAtSystem(activeSystemIndex)
            systemRail.currentIndex = activeSystemIndex
            activateCachedSystemSort()
            page = "games"
            gameRail.currentIndex = 0
            return
        }
        page = "home"
        homeViewMode = "covers"
        homeZone = 0
    }

    function homeSystemStatsText() {
        var index = Math.max(0, systemRail.currentIndex)
        if (index === 0)
            return romGameCount() + " TITLES  •  " + Math.max(0, systemModel.count - 1) +
                    " SYSTEMS  •  ONE UNIFIED LIBRARY"
        return systemGameCount(index) + " TITLES  •  " +
                String(systemModel.get(index).years) + "  •  DIRECT LAUNCH READY"
    }

    function homeSystemInstructionText() {
        if (homeListFocusColumn === 0)
            return homeShelfName(homeListCategory) +
                    "  •  RANDOM PREVIEW  •  A  SELECT SYSTEM"
        return homeShelfName(homeListCategory) +
                "  •  A  PLAY  •  B  BACK TO SYSTEM LIST"
    }

    function useWhiteBrandLogo(index) {
        if (brandSlugForSystem(index) !== "nintendo") return false
        var color = root.accent
        // Nintendo's official red wordmark loses contrast against a red user
        // accent. Preserve the identity mark by rendering that PNG in white.
        return color.r > 0.62 && color.g < 0.42 && color.b < 0.42
    }

    function setRightStickViewSwitching(enabled) {
        rightStickViewSwitchingEnabled = Boolean(enabled)
        api.memory.set("lucentRightStickViewSwitching",
                       rightStickViewSwitchingEnabled)
    }

    function setViewTransitions(enabled) {
        viewTransitionsEnabled = Boolean(enabled)
        api.memory.set("lucentViewTransitions", viewTransitionsEnabled)
    }

    function showCoverView() {
        page = "home"
        homeViewMode = "covers"
        homeZone = 0
        homeListFocusColumn = 0
        api.memory.set("thoriumHomeView", homeViewMode)
        scheduleNavigationPersistence()
        chooseSystemWallpaper(systemRail.currentIndex)
        Qt.callLater(function() { root.activateHomePreview(false) })
        root.forceActiveFocus()
    }

    function showListView() {
        page = "home"
        homeViewMode = "list"
        homeListCategory = Math.max(1, Math.min(7, homeListCategory))
        homeZone = homeListCategory
        homeListFocusColumn = 0
        api.memory.set("thoriumHomeView", homeViewMode)
        scheduleNavigationPersistence()
        rebuildHomeList()
        root.forceActiveFocus()
    }

    function showAllSystemsView() {
        openSystemInPlace(0)
        page = "games"
        root.forceActiveFocus()
    }

    function handleRightStickViewKey(event) {
        if (!rightStickViewSwitchingEnabled) return false
        if (event.key === Qt.Key_F1) {
            showCoverView()
            return true
        }
        if (event.key === Qt.Key_F2) {
            showListView()
            return true
        }
        if (event.key === Qt.Key_F3 || event.key === Qt.Key_F4) {
            if (page !== "games")
                showAllSystemsView()
            else
                stepOpenSystem(event.key === Qt.Key_F3 ? -1 : 1)
            return true
        }
        return false
    }

    function stepHomeListPage(direction) {
        if (homeListFocusColumn === 0) {
            var systemPage = 9
            systemRail.currentIndex = Math.max(0, Math.min(systemModel.count - 1,
                    systemRail.currentIndex + (direction < 0 ? -systemPage : systemPage)))
            return
        }
        if (homeListEntries.length <= 0) return
        var gamePage = 7
        homeListRail.currentIndex = Math.max(0, Math.min(homeListEntries.length - 1,
                homeListRail.currentIndex + (direction < 0 ? -gamePage : gamePage)))
    }

    function settingTitle(index) {
        var titles = ["SYSTEM WALLPAPER MODE", "GAME PREVIEW PLACEMENT",
                "PREVIEW VIDEO SOUND", "LIQUID GLASS",
                "ACCENT COLOR GROUPING", "CUSTOMIZE ACCENT COLORS",
                "SYSTEM-MATCHED STICK LEDS", "STICK LED BRIGHTNESS",
                "START VIEW", "COVER VIEW ROWS", "COVER ROW ORDER",
                "RIGHT STICK VIEW SWITCHING", "VIEW TRANSITIONS",
                "ABOUT LUCENT", "UPDATE LIBRARY & LUCENT",
                "PIP / BOX ART ORDER"]
        return titles[index]
    }

    function settingDescription(index) {
        var descriptions = [
            "Preloaded static angle; rerolls only after you leave",
            "Automatic detects the screen count; choose PIP or Off manually",
            "Sound follows the visible preview; preloaded neighbors stay silent",
            "Optional refractive blur and wallpaper distortion for controls",
            "Group accents by platform family or give every system its own hue",
            "Choose any hue, saturation, and lightness for each active group",
            "Instantly matches the accent of the highlighted system",
            "Manual hardware level from 1–100%; defaults to a subtle 2%",
            "Choose the layout or detected system shown on a fresh launch",
            "Show one large shelf by default, or expose two or three at once",
            "Reorder Systems, Continue, Most Played, Recently Added, and score shelves",
            "Up: Cover  •  Down: List  •  Left/Right: All Systems then systems",
            "Optional slide, fade, and scale motion when changing Lucent views",
            "Pegasus attribution, licenses, trademarks, and Lucent version",
            "Scan games and check GitHub for app and theme updates",
            "Swap the video and box-art positions on single-screen devices"
        ]
        return descriptions[index]
    }

    function settingValue(index) {
        if (index === 0) return "STATIC"
        if (index === 1) return previewPlacementLabel()
        if (index === 2) return previewSoundEnabled ? "ON" : "OFF"
        if (index === 3) return liquidGlassEnabled ? "ON" : "OFF"
        if (index === 4) return accentGrouping === "system" ? "BY SYSTEM" : "BY PLATFORM FAMILY"
        if (index === 5) return "EDIT"
        if (index === 6) return systemLedEnabled ? "ON" : "OFF"
        if (index === 7) return systemLedBrightness + "%"
        if (index === 8) return startViewLabel(startViewPreference)
        if (index === 9) return coverViewRowCount + (coverViewRowCount === 1 ? " ROW" : " ROWS")
        if (index === 10) return "EDIT"
        if (index === 11) return rightStickViewSwitchingEnabled ? "ON" : "OFF"
        if (index === 12) return viewTransitionsEnabled ? "ON" : "OFF"
        if (index === 13) return "VIEW"
        if (index === 14) return "RUN"
        if (index === 15 && !dualScreenDevice)
            return singleScreenMediaSwapped ? "BOX LEFT  •  VIDEO RIGHT" :
                                              "VIDEO LEFT  •  BOX RIGHT"
        return ""
    }

    function activateSetting(direction) {
        if (settingsIndex === 0) {
            // Static, predecoded system artwork is the sole supported mode.
            systemMotionEnabled = false
            api.memory.set("thoriumSystemMotion", false)
        } else if (settingsIndex === 1) {
            cyclePreviewPlacement(direction === 0 ? 1 : direction)
        } else if (settingsIndex === 2) {
            setPreviewSoundEnabled(!previewSoundEnabled)
        } else if (settingsIndex === 3) {
            liquidGlassEnabled = !liquidGlassEnabled
            api.memory.set("thoriumLiquidGlassEnabled", liquidGlassEnabled)
        } else if (settingsIndex === 4) {
            setAccentGrouping(accentGrouping === "family" ? "system" : "family")
        } else if (settingsIndex === 5) {
            openAccentEditor()
        } else if (settingsIndex === 6) {
            setSystemLedEnabled(!systemLedEnabled)
        } else if (settingsIndex === 7) {
            setSystemLedBrightness(systemLedBrightness + (direction < 0 ? -1 : 1))
        } else if (settingsIndex === 8) {
            cycleStartView(direction === 0 ? 1 : direction)
        } else if (settingsIndex === 9) {
            var rowStep = direction < 0 ? -1 : 1
            setCoverViewRowCount(((coverViewRowCount - 1 + rowStep + 3) % 3) + 1)
        } else if (settingsIndex === 10) {
            openCoverOrderEditor()
        } else if (settingsIndex === 11) {
            setRightStickViewSwitching(!rightStickViewSwitchingEnabled)
        } else if (settingsIndex === 12) {
            setViewTransitions(!viewTransitionsEnabled)
        } else if (settingsIndex === 13) {
            aboutOpen = true
        } else if (settingsIndex === 14) {
            updatePromptDismissed = false
            importState = "idle"
            importStatusInitialized = true
            importToastVisible = true
            startImportScan()
            requestPreviewEndpoint("update/check")
        } else if (settingsIndex === 15 && !dualScreenDevice) {
            setSingleScreenMediaSwapped(!singleScreenMediaSwapped)
        }
    }

    function detectPreviewCapabilities() {
        var request = new XMLHttpRequest()
        request.onreadystatechange = function() {
            if (request.readyState !== XMLHttpRequest.DONE || request.status !== 200)
                return
            try {
                var capabilities = JSON.parse(request.responseText)
                var detectedDualScreen = Boolean(capabilities.dualScreen)
                if (root.dualScreenDevice !== detectedDualScreen) {
                    root.dualScreenDevice = detectedDualScreen
                    if (root.page === "home" && root.homeViewMode === "list")
                        root.activateHomeListPreview()
                    else if (root.page === "home" && root.homeZone === 0)
                        root.activateHomePreview(false)
                    else if (root.page === "games")
                        root.activateGamePreview()
                    else
                        root.activateShelfPreview(root.homeZone)
                }
            } catch (error) {
                // Default to Thor's physical lower display if an older
                // companion does not expose capability discovery yet.
            }
        }
        request.open("GET", "http://127.0.0.1:43821/capabilities", true)
        request.send()
    }

    function singleSource(index) {
        if (index === 0) return singleSourceA
        if (index === 1) return singleSourceB
        return singleSourceC
    }

    function singlePlayer(index) {
        if (index === 0) return singleVideoA
        if (index === 1) return singleVideoB
        return singleVideoC
    }

    function setSingleSource(index, source) {
        source = String(source || "")
        if (singleSource(index) === source) return
        if (index === 0) singleSourceA = source
        else if (index === 1) singleSourceB = source
        else singleSourceC = source
    }

    function findSingleSource(source) {
        source = String(source || "")
        for (var index = 0; index < 3; ++index) {
            if (source !== "" && singleSource(index) === source)
                return index
        }
        return -1
    }

    function ensureSingleWarm(source, protectedA, protectedB) {
        source = String(source || "")
        if (source === "") return -1
        var existing = findSingleSource(source)
        if (existing >= 0) return existing
        for (var index = 0; index < 3; ++index) {
            if (index !== protectedA && index !== protectedB) {
                setSingleSource(index, source)
                return index
            }
        }
        return -1
    }

    function promoteSingleVideo(index) {
        if (index !== singleTargetSlot) return
        singleCurrentSlot = index
        singleTargetSlot = -1
    }

    function setSingleScreenPreview(current, previous, next) {
        current = String(current || "")
        if (current === "") {
            singleTargetSlot = -1
            singleCurrentSlot = -1
            return
        }
        var currentSlot = findSingleSource(current)
        if (currentSlot < 0) {
            currentSlot = (singleCurrentSlot + 1 + 3) % 3
            setSingleSource(currentSlot, current)
        }
        singleTargetSlot = currentSlot
        var previousSlot = ensureSingleWarm(previous, currentSlot, -1)
        ensureSingleWarm(next, currentSlot, previousSlot)
        var player = singlePlayer(currentSlot)
        if (player.position > 0 || player.status === MediaPlayer.Buffered)
            promoteSingleVideo(currentSlot)
    }

    function hideBottomPreview() {
        var request = new XMLHttpRequest()
        request.open("GET", "http://127.0.0.1:43821/hide", true)
        request.send()
    }

    function blankBottomPreviewNow() {
        // Run before the system rail paints its next selection. This prevents
        // the outgoing system's final decoded frame from surviving beneath
        // the new system while its random preview is being selected/decoded.
        var request = new XMLHttpRequest()
        try {
            previewRequestSequence += 1
            request.open("GET", "http://127.0.0.1:43821/transition?seq=" +
                         previewRequestSequence, true)
            request.send()
        } catch (error) {
            // The preview companion is optional; navigation must stay usable.
        }
    }

    function sendPreviewHeartbeat() {
        var request = new XMLHttpRequest()
        request.open("GET", "http://127.0.0.1:43821/heartbeat", true)
        request.send()
    }

    function applyImportStatus(payload) {
        if (!payload) return
        var previousState = importState
        var establishSilentBaseline = !importStatusInitialized &&
                ((payload.state || "idle") === "complete" ||
                 (payload.state || "idle") === "idle")
        importStatusInitialized = true
        var fingerprint = String(payload.state || "idle") + "|" +
                String(payload.progress || 0) + "|" + String(payload.message || "") + "|" +
                String(payload.detail || "") + "|" + String(payload.current || 0) + "|" +
                String(payload.total || 0) + "|" +
                String(payload.identified || 0) + "|" + String(payload.added || 0) + "|" +
                String(Boolean(payload.needsReload))
        var changed = fingerprint !== lastImportFingerprint
        lastImportFingerprint = fingerprint
        importState = payload.state || "idle"
        importProgress = Number(payload.progress || 0)
        importMessage = payload.message || ""
        importDetail = payload.detail || ""
        importCurrent = Number(payload.current || 0)
        importTotal = Number(payload.total || 0)
        importTitles = payload.titles || []
        importIdentified = Number(payload.identified || 0)
        importAdded = Number(payload.added || 0)
        importNeedsReload = Boolean(payload.needsReload)
        if (importState !== "complete" && importState !== "idle")
            importReloadRequested = false
        if (establishSilentBaseline) {
            importToastVisible = false
            return
        }
        if (importState === "complete") {
            // Status polling can continue returning "complete" forever. Show
            // the result once on the state transition, then dismiss it on an
            // absolute timer that later polls cannot restart.
            if (previousState !== "complete") {
                importToastVisible = true
                importToastDismiss.restart()
                if (importNeedsReload && !importReloadRequested) {
                    importReloadRequested = true
                    importedLibraryReload.restart()
                }
            }
        } else if (importState === "error") {
            if (previousState !== "error") {
                importToastVisible = true
                importToastDismiss.restart()
            }
        } else if (importState !== "idle") {
            importToastVisible = true
        } else if (changed) {
            importToastVisible = false
        }
    }

    function requestImport(endpoint) {
        var request = new XMLHttpRequest()
        request.onreadystatechange = function() {
            if (request.readyState !== XMLHttpRequest.DONE || request.status < 200 || request.status >= 300)
                return
            try {
                root.applyImportStatus(JSON.parse(request.responseText))
            } catch (error) {
                // Import status must never interfere with navigation.
            }
        }
        request.open("GET", "http://127.0.0.1:43821/import/" + endpoint, true)
        request.send()
    }

    function startImportScan() {
        requestImport("scan")
    }

    function startMaintenanceRescan() {
        endSearch()
        updatePromptDismissed = false
        importState = "scanning"
        importProgress = 0.01
        importMessage = "Starting full library maintenance…"
        importDetail = "Checking storage, Downloads, game media, and Lucent updates"
        importStatusInitialized = true
        importToastVisible = true

        var request = new XMLHttpRequest()
        request.onreadystatechange = function() {
            if (request.readyState !== XMLHttpRequest.DONE ||
                    request.status < 200 || request.status >= 300)
                return
            try {
                root.applyImportStatus(JSON.parse(request.responseText))
            } catch (error) {
                // The normal status poll will recover the visible state.
            }
        }
        request.open("GET", "http://127.0.0.1:43821/maintenance/rescan", true)
        request.send()
    }

    function openLucentBrowser() {
        endSearch()
        requestPreviewEndpoint("browser/open")
    }

    function pollImportStatus() {
        requestImport("status")
    }

    function usesBottomScreen(game) {
        var index = systemIndexForGame(game)
        if (index < 0) return false
        var folder = systemModel.get(index).folder
        return folder === "nds" || folder === "n3ds" || folder === "wiiu"
    }

    function stopBottomPreviewForLaunch(game) {
        // Wait for the companion to acknowledge the transition before Pegasus
        // yields the foreground. An asynchronous request could be suspended as
        // the emulator opened, leaving the previous game's movie playing.
        var endpoint = usesBottomScreen(game) ? "hide" : "blank"
        var request = new XMLHttpRequest()
        try {
            request.open("GET", "http://127.0.0.1:43821/" + endpoint, false)
            request.send()
        } catch (error) {
            // Launching the game is still preferable if the optional preview
            // companion is temporarily unavailable.
        }
    }

    function wrappedSystemIndex(index) {
        var count = systemModel.count
        if (count <= 0) return -1
        return (index % count + count) % count
    }

    function collectionAtSystem(index) {
        var wrapped = wrappedSystemIndex(index)
        if (wrapped < 0) return null
        return collectionNamed(systemModel.get(wrapped).collectionName)
    }

    function homePreviewCandidates(systemIndex) {
        var wrapped = wrappedSystemIndex(systemIndex)
        if (wrapped < 0) return []
        var cached = homePreviewCandidatesBySystem[wrapped]
        if (cached) return cached
        var collection = collectionAtSystem(wrapped)
        var videoMatches = []
        var artworkMatches = []
        if (systemModel.get(wrapped).folder === "all") {
            for (var allIndex = 0; allIndex < api.allGames.count; ++allIndex) {
                var allCandidate = api.allGames.get(allIndex)
                if (!allCandidate || systemIndexForGame(allCandidate) <= 0) continue
                if (allCandidate.assets.video)
                    videoMatches.push(allCandidate)
                else if (artwork(allCandidate))
                    artworkMatches.push(allCandidate)
            }
        } else if (collection) {
            for (var index = 0; index < collection.games.count; ++index) {
                var candidate = collection.games.get(index)
                if (!candidate) continue
                if (candidate.assets.video)
                    videoMatches.push(candidate)
                else if (artwork(candidate))
                    artworkMatches.push(candidate)
            }
        }
        cached = videoMatches.length > 0 ? videoMatches : artworkMatches
        homePreviewCandidatesBySystem[wrapped] = cached
        return cached
    }

    function randomHomeVideoGame(systemIndex, avoidGame) {
        var candidates = homePreviewCandidates(systemIndex)
        if (candidates.length === 0) return null
        if (candidates.length === 1) return candidates[0]
        var selected = candidates[Math.floor(Math.random() * candidates.length)]
        if (selected === avoidGame) {
            var current = candidates.indexOf(selected)
            selected = candidates[(current + 1 + Math.floor(Math.random() *
                       (candidates.length - 1))) % candidates.length]
        }
        return selected
    }

    function randomVideoGame(collection, avoidGame) {
        if (!collection || collection.games.count <= 0) return null
        var videoMatches = []
        var artworkMatches = []
        for (var i = 0; i < collection.games.count; ++i) {
            var candidate = collection.games.get(i)
            if (!candidate || candidate === avoidGame)
                continue
            if (candidate.assets.video)
                videoMatches.push(candidate)
            else if (artwork(candidate))
                artworkMatches.push(candidate)
        }
        var matches = videoMatches.length > 0 ? videoMatches : artworkMatches
        if (matches.length === 0 && avoidGame)
            return avoidGame
        if (matches.length === 0)
            return collection.games.get(Math.floor(Math.random() * collection.games.count))
        return matches[Math.floor(Math.random() * matches.length)]
    }

    function previewSlots() {
        return [previewA, previewB, previewC]
    }

    function slotFor(mode, systemIndex, gameIndex) {
        var slots = previewSlots()
        for (var i = 0; i < slots.length; ++i) {
            if (slots[i].previewMode === mode &&
                    slots[i].systemIndex === systemIndex &&
                    slots[i].gameIndex === gameIndex)
                return slots[i]
        }
        return null
    }

    function freeSlot(excludedA, excludedB) {
        var slots = previewSlots()
        for (var i = 0; i < slots.length; ++i) {
            if (slots[i] !== excludedA && slots[i] !== excludedB)
                return slots[i]
        }
        return slots[0]
    }

    function assignSlot(slot, mode, systemIndex, gameIndex, game) {
        if (!slot) return
        if (slot.previewMode === mode && slot.systemIndex === systemIndex &&
                slot.gameIndex === gameIndex && slot.previewGame === game)
            return
        slot.previewMode = mode
        slot.systemIndex = systemIndex
        slot.gameIndex = gameIndex
        slot.previewGame = game
    }

    function prepareHomeNeighbor(systemIndex, reservedSlot) {
        var wrapped = wrappedSystemIndex(systemIndex)
        var existing = slotFor("home", wrapped, -1)
        if (existing && existing !== activePreviewSlot)
            return existing
        var slot = freeSlot(activePreviewSlot, reservedSlot)
        var selected = lastHomeGameBySystem[wrapped] ||
                randomHomeVideoGame(wrapped, null)
        lastHomeGameBySystem[wrapped] = selected
        assignSlot(slot, "home", wrapped, -1,
                   selected)
        return slot
    }

    function activateHomePreview(forceReroll) {
        if (!previewReady) return
        var index = wrappedSystemIndex(systemRail.currentIndex)
        if (lastSystemPreviewIndex >= 0 && lastSystemPreviewIndex !== index) {
            departedSystemIndex = lastSystemPreviewIndex
            rerollDepartedPreview.restart()
        }
        lastSystemPreviewIndex = index
        var current = slotFor("home", index, -1) || freeSlot(null, null)
        var avoid = lastHomeGameBySystem[index] || null
        var selected = !forceReroll && avoid ? avoid :
                randomHomeVideoGame(index, avoid)
        assignSlot(current, "home", index, -1, selected)
        lastHomeGameBySystem[index] = selected
        activePreviewSlot = current
        homePreviewGame = current.previewGame
        var previous = prepareHomeNeighbor(index - 1, null)
        var next = prepareHomeNeighbor(index + 1, previous)
        sendBottomPreview(homePreviewGame,
                          previous ? previous.previewGame : null,
                          next ? next.previewGame : null, null)
    }

    function gameAt(collection, index) {
        if (!collection || collection.games.count <= 0) return null
        var wrapped = (index % collection.games.count + collection.games.count) % collection.games.count
        return collection.games.get(wrapped)
    }

    function prepareGameNeighbor(systemIndex, collection, gameIndex, reservedSlot) {
        if ((!allSystemsActive && !collection) || activeGameCount <= 1) return null
        var wrapped = (gameIndex % activeGameCount + activeGameCount) % activeGameCount
        var expectedGame = gameAtDisplayIndex(wrapped)
        var existing = slotFor("game", systemIndex, wrapped)
        if (existing && existing !== activePreviewSlot &&
                existing.previewGame === expectedGame)
            return existing
        var slot = existing && existing !== activePreviewSlot ? existing :
                   freeSlot(activePreviewSlot, reservedSlot)
        assignSlot(slot, "game", systemIndex, wrapped, expectedGame)
        return slot
    }

    function activateGamePreview() {
        if (!previewReady || page !== "games") return
        var collection = activeCollection
        var systemIndex = allSystemsActive ? activeGameSystemIndex : activeSystemIndex
        var gameIndex = gameRail.currentIndex
        if ((!allSystemsActive && !collection) || activeGameCount <= 0) {
            homePreviewGame = null
            var emptySlot = activePreviewSlot || previewA
            assignSlot(emptySlot, "game", systemIndex, -1, null)
            activePreviewSlot = emptySlot
            // An empty collection has no selected game. Explicitly clear the
            // secondary display instead of leaving the departed system's last
            // decoded frame running underneath it.
            if (useBottomPreview())
                requestPreviewEndpoint("blank")
            else
                setSingleScreenPreview("", "", "")
            return
        }

        var expectedGame = gameAtDisplayIndex(gameIndex)
        var current = slotFor("game", systemIndex, gameIndex)
        if (!current) {
            current = freeSlot(null, null)
        }
        // A sort changes the game represented by an index without changing
        // the index itself. Never reuse the old movie merely because the row
        // number matches.
        assignSlot(current, "game", systemIndex, gameIndex, expectedGame)
        activePreviewSlot = current
        var previous = prepareGameNeighbor(systemIndex, collection, gameIndex - 1, null)
        var next = prepareGameNeighbor(systemIndex, collection, gameIndex + 1, previous)
        queueUpperArtwork(current.previewGame,
                          previous ? previous.previewGame : null,
                          next ? next.previewGame : null)
        queueBoxArtwork(current.previewGame,
                        previous ? previous.previewGame : null,
                        next ? next.previewGame : null)
        var homeAux = lastHomeGameBySystem[systemIndex]
        if (!homeAux) {
            homeAux = randomHomeVideoGame(systemIndex, current.previewGame)
            lastHomeGameBySystem[systemIndex] = homeAux
        }
        sendBottomPreview(current.previewGame,
                          previous ? previous.previewGame : null,
                          next ? next.previewGame : null, homeAux)
    }

    function activateShelfPreview(zone) {
        if (!previewReady) return
        var model = homeShelfModel(zone)
        var rail = homeShelfRail(zone)
        if (!model || model.count <= 0) return
        var game = homeShelfGameAt(zone, rail.currentIndex)
        var slot = freeSlot(null, null)
        assignSlot(slot, "shelf" + zone, -1, rail.currentIndex, game)
        activePreviewSlot = slot
        var previous = homeShelfGameAt(zone,
                (rail.currentIndex - 1 + model.count) % model.count)
        var next = homeShelfGameAt(zone, (rail.currentIndex + 1) % model.count)
        queueUpperArtwork(game, previous, next)
        queueBoxArtwork(game, previous, next)
        sendBottomPreview(game, previous, next, null)
    }

    function activateHomeListPreview() {
        if (!previewReady || page !== "home" || homeViewMode !== "list") return
        if (homeListEntries.length <= 0 || homeListRail.currentIndex < 0) {
            homePreviewGame = null
            currentBottomPreviewGame = null
            currentBottomPreviewSequence = 0
            requestPreviewEndpoint("blank")
            return
        }
        var selectedIndex = homeListRail.currentIndex
        var game = homeListGameAt(selectedIndex)
        var slot = freeSlot(null, null)
        assignSlot(slot, "home-list-" + homeListCategory,
                   systemRail.currentIndex, selectedIndex, game)
        activePreviewSlot = slot
        var previous = homeListGameAt((selectedIndex - 1 + homeListEntries.length) %
                                      homeListEntries.length)
        var next = homeListGameAt((selectedIndex + 1) % homeListEntries.length)
        queueUpperArtwork(game, previous, next)
        queueBoxArtwork(game, previous, next)
        sendBottomPreview(game, previous, next, null)
    }

    function activateRecentPreview() {
        activateShelfPreview(1)
    }

    function launch(game) {
        if (!game) return
        navigationPersistence.stop()
        api.memory.set("thoriumSystem", page === "games" ? activeSystemIndex : systemRail.currentIndex)
        api.memory.set("thoriumGame", gameRail.currentIndex)
        api.memory.set("thoriumPage", page)
        // Qt reconstructs this scene when some emulators release the display.
        // Persisting this one-shot marker distinguishes that return from a
        // genuine Pegasus startup, where the Downloads scan should run.
        api.memory.set("parallaxReturningFromGame", true)
        stopBottomPreviewForLaunch(game)
        game.launch()
    }

    function scheduleNavigationPersistence() {
        navigationPersistencePending = true
        navigationPersistence.restart()
    }

    function flushNavigationPersistence() {
        if (!navigationPersistencePending) return
        navigationPersistencePending = false
        api.memory.set("thoriumSystem", page === "games" ?
                       activeSystemIndex : systemRail.currentIndex)
        api.memory.set("thoriumGame", page === "games" ? gameRail.currentIndex : 0)
        api.memory.set("thoriumPage", page)
        api.memory.set("thoriumSortMode", sortMode)
        api.memory.set("thoriumGameView", gameViewMode)
        api.memory.set("thoriumHomeView", homeViewMode)
    }

    function enterCollection() {
        // Pay the model/sort cost only when the user opens this collection.
        // Resolve directly from the highlighted index in this event turn. A
        // derived selectedCollection binding may not have re-evaluated yet
        // after very fast D-pad navigation.
        activeSystemIndex = systemRail.currentIndex
        allSystemsActive = activeSystemIndex === 0
        activeCollection = allSystemsActive ? null : collectionAtSystem(activeSystemIndex)
        activateCachedSystemSort()
        page = "games"
        gameRail.currentIndex = 0
        scheduleNavigationPersistence()
        Qt.callLater(function() { root.activateGamePreview() })
        root.forceActiveFocus()
    }

    function returnHome() {
        systemRail.currentIndex = activeSystemIndex
        page = "home"
        homeZone = 0
        scheduleNavigationPersistence()
        chooseSystemWallpaper(systemRail.currentIndex)
        systemRail.positionViewAtIndex(systemRail.currentIndex, ListView.Center)
        Qt.callLater(function() { root.activateHomePreview(false) })
        systemRail.forceActiveFocus()
    }

    function openSystemInPlace(index) {
        var target = wrappedSystemIndex(index)
        if (target < 0) return
        // Rebind the collection before moving the shared system rail. Its
        // currentIndex callback used to observe the old model and submit one
        // stale preview whenever L2/R2 changed platform.
        activeSystemIndex = target
        allSystemsActive = target === 0
        activeCollection = allSystemsActive ? null : collectionAtSystem(target)
        activateCachedSystemSort()
        systemRail.currentIndex = target
        gameRail.currentIndex = 0
        scheduleNavigationPersistence()
        // Keep the root, rather than a delegate that is about to be destroyed
        // by the source-model swap, as the key owner. Losing focus during that
        // swap was what made the first opposite-trigger press disappear.
        root.forceActiveFocus()
        systemOpenCommit.restart()
    }

    function stepOpenSystem(direction) {
        openSystemInPlace(activeSystemIndex + (direction < 0 ? -1 : 1))
    }

    function isLeftTrigger(event) {
        // AYN's Odin Controller exposes L2 both as Android BUTTON_L2 and Linux
        // BTN_TL2. Qt versions used by Pegasus disagree on whether that arrives
        // as a Qt gamepad enum, an Android virtual key, or only a scan code.
        return event.key === 0x01000086 || event.key === 1048581 || event.key === 104 ||
               event.nativeVirtualKey === 104 || event.nativeScanCode === 312
    }

    function isRightTrigger(event) {
        return event.key === 0x01000087 || event.key === 1048584 || event.key === 105 ||
               event.nativeVirtualKey === 105 || event.nativeScanCode === 313
    }

    function triggerDirection(event) {
        // Prefer the Thor's unambiguous Linux scan codes before Pegasus'
        // generic PageUp/PageDown aliases. Some Qt builds cache the previous
        // alias for one event when the user reverses trigger direction.
        if (event.nativeScanCode === 312) return -1
        if (event.nativeScanCode === 313) return 1
        if (isLeftTrigger(event)) return -1
        if (isRightTrigger(event)) return 1
        return 0
    }

    // Qt occasionally drops the pressed edge of the first opposite trigger
    // after a collection model swap, even though Android still delivers its
    // released edge. Track handled presses per physical trigger so release can
    // perform exactly one fallback step without doubling ordinary presses.
    property bool leftTriggerPressHandled: false
    property bool rightTriggerPressHandled: false

    function rememberHandledTrigger(direction) {
        if (direction < 0)
            leftTriggerPressHandled = true
        else if (direction > 0)
            rightTriggerPressHandled = true
    }

    function focusHomeZone(zone) {
        homeZone = Math.max(0, Math.min(7, zone))
        if (homeZone === 0) {
            chooseSystemWallpaper(systemRail.currentIndex)
            activateHomePreview(false)
            systemRail.forceActiveFocus()
        } else {
            activateShelfPreview(homeZone)
            homeShelfRail(homeZone).forceActiveFocus()
        }
    }

    function stepHomeShelf(direction) {
        var rail = homeShelfRail(homeZone)
        if (!rail) return
        if (direction < 0)
            rail.decrementCurrentIndex()
        else
            rail.incrementCurrentIndex()
    }

    function stepSystem(direction) {
        var count = systemModel.count
        if (count <= 0) return
        // Match the game rail's cheap navigation path. ApplyRange centers the
        // selected delegate itself; forcing positionViewAtIndex here caused a
        // synchronous relayout on every D-pad press.
        if (direction < 0)
            systemRail.decrementCurrentIndex()
        else
            systemRail.incrementCurrentIndex()
    }

    function updateClock() {
        var now = new Date()
        var hours = now.getHours()
        var minutes = now.getMinutes()
        var suffix = hours >= 12 ? "PM" : "AM"
        hours = hours % 12
        if (hours === 0) hours = 12
        clockText = hours + ":" + (minutes < 10 ? "0" : "") + minutes + " " + suffix
    }

    function escapedSearchPattern(value) {
        var text = String(value || "")
        var special = "\\^$.*+?()[]{}|"
        var result = ""
        for (var i = 0; i < text.length; ++i) {
            var character = text.charAt(i)
            result += special.indexOf(character) >= 0 ? "\\" + character : character
        }
        return result
    }

    function beginSearch() {
        // Search the complete library from home. When invoked inside a system,
        // preserve that system as the search scope.
        if (page === "home") {
            activeSystemIndex = 0
            allSystemsActive = true
            activeCollection = null
            systemRail.currentIndex = 0
            page = "games"
            gameRail.currentIndex = 0
        }
        searchOpen = true
        Qt.callLater(function() {
            searchField.forceActiveFocus()
            Qt.inputMethod.show()
        })
    }

    function endSearch() {
        Qt.inputMethod.reset()
        searchOpen = false
        searchQuery = ""
        searchField.text = ""
        searchField.focus = false
        Qt.inputMethod.hide()
        gameRail.currentIndex = 0
        searchChangeCommit.restart()
        root.forceActiveFocus()
    }

    Component.onCompleted: {
        rebuildRecentlyAddedModel()
        rebuildVisibleSystems()
        detectPreviewCapabilities()
        systemMotionEnabled = false
        api.memory.set("thoriumSystemMotion", false)
        previewPlacementMode = api.memory.has("thoriumPreviewPlacement") ?
                api.memory.get("thoriumPreviewPlacement") : "auto"
        singleScreenMediaSwapped = api.memory.has("lucentSingleScreenMediaSwapped") ?
                Boolean(api.memory.get("lucentSingleScreenMediaSwapped")) : false
        coverViewRowCount = api.memory.has("lucentCoverViewRowCount") ?
                Math.max(1, Math.min(3, Number(api.memory.get("lucentCoverViewRowCount")))) : 1
        coverRowOrder = validatedCoverRowOrder(api.memory.has("lucentCoverRowOrder") ?
                String(api.memory.get("lucentCoverRowOrder")) : "[0,1,2,3,4,5,6,7]")
        // The refractive shader remains available as an explicit visual
        // preference, but the calm, undistorted surface is the default.
        liquidGlassEnabled = api.memory.has("thoriumLiquidGlassEnabled") ?
                Boolean(api.memory.get("thoriumLiquidGlassEnabled")) : false
        rightStickViewSwitchingEnabled =
                api.memory.has("lucentRightStickViewSwitching") ?
                Boolean(api.memory.get("lucentRightStickViewSwitching")) : true
        viewTransitionsEnabled = api.memory.has("lucentViewTransitions") ?
                Boolean(api.memory.get("lucentViewTransitions")) : false
        accentGrouping = api.memory.has("lucentAccentGrouping") ?
                String(api.memory.get("lucentAccentGrouping")) : "family"
        try {
            customFamilyAccents = api.memory.has("lucentCustomFamilyAccents") ?
                    JSON.parse(String(api.memory.get("lucentCustomFamilyAccents"))) : ({})
        } catch (familyError) { customFamilyAccents = ({}) }
        try {
            customSystemAccents = api.memory.has("lucentCustomSystemAccents") ?
                    JSON.parse(String(api.memory.get("lucentCustomSystemAccents"))) : ({})
        } catch (systemError) { customSystemAccents = ({}) }
        try {
            removedHomeCategoryGameIds = api.memory.has("lucentRemovedHomeCategoryGameIds") ?
                    JSON.parse(String(api.memory.get("lucentRemovedHomeCategoryGameIds"))) :
                    ({ "1": {}, "2": {}, "3": {} })
        } catch (removedListError) {
            removedHomeCategoryGameIds = ({ "1": {}, "2": {}, "3": {} })
        }
        rebuildRecentlyAddedModel()
        applyAccentPalette()
        systemLedEnabled = api.memory.has("lucentSystemLedEnabled") ?
                Boolean(api.memory.get("lucentSystemLedEnabled")) : true
        systemLedBrightness = api.memory.has("lucentSystemLedBrightness") ?
                Math.max(1, Math.min(100,
                    Number(api.memory.get("lucentSystemLedBrightness")))) : 2
        // Split the old combined SYSTEM/manual row into independent enabled
        // and brightness preferences. Migrate every existing install once to
        // the requested safe default: enabled, system-matched, one percent.
        if (!api.memory.has("lucentSplitLedControlsV1")) {
            systemLedEnabled = true
            systemLedBrightness = 2
            api.memory.set("lucentSystemLedEnabled", true)
            api.memory.set("lucentSystemLedBrightness", 2)
            api.memory.set("lucentSplitLedControlsV1", true)
        }
        // Existing 3.0.40/3.0.41 installs were intentionally migrated to 1%.
        // Move that exact old default to the newly requested 2% once without
        // overwriting a brightness the user had already customized.
        if (!api.memory.has("lucentLedDefaultTwoPercentV1")) {
            if (systemLedBrightness === 1) {
                systemLedBrightness = 2
                api.memory.set("lucentSystemLedBrightness", 2)
            }
            api.memory.set("lucentLedDefaultTwoPercentV1", true)
        }
        startViewPreference = api.memory.has("lucentStartView") ?
                String(api.memory.get("lucentStartView")) : "cover"
        // Sound is on by default.  Migrate existing installs once because the
        // original theme persisted OFF even though the requested default is ON.
        if (!api.memory.has("parallaxPreviewSoundDefaultV1")) {
            previewSoundEnabled = true
            api.memory.set("thoriumPreviewSound", true)
            api.memory.set("parallaxPreviewSoundDefaultV1", true)
        } else {
            previewSoundEnabled = api.memory.has("thoriumPreviewSound") ?
                    Boolean(api.memory.get("thoriumPreviewSound")) : true
        }
        requestPreviewEndpoint("settings/sound?enabled=" +
                (previewSoundEnabled ? "1" : "0"))
        systemLedCommit.restart()
        var rememberedSystem = api.memory.has("thoriumSystem") ? api.memory.get("thoriumSystem") : 0
        // Version 1 inserts All Systems ahead of the old Arcade index. Shift a
        // saved pre-aggregate selection once so it still points to the same
        // physical platform after the model gains its new first item.
        if (!api.memory.has("thoriumAllSystemsIndexV1") && api.memory.has("thoriumSystem")) {
            rememberedSystem += 1
            api.memory.set("thoriumAllSystemsIndexV1", true)
        }
        systemRail.currentIndex = Math.max(0, Math.min(systemModel.count - 1, rememberedSystem))
        var rememberedGame = api.memory.has("thoriumGame") ? api.memory.get("thoriumGame") : 0
        var rememberedPage = api.memory.has("thoriumPage") ? api.memory.get("thoriumPage") : "home"
        if (!api.memory.has("thoriumCriticSortDefaultV3")) {
            sortMode = "critic"
            api.memory.set("thoriumSortMode", sortMode)
            api.memory.set("thoriumCriticSortDefaultV3", true)
        } else {
            sortMode = api.memory.has("thoriumSortMode") ? api.memory.get("thoriumSortMode") : "user"
        }
        gameViewMode = api.memory.has("thoriumGameView") ?
                api.memory.get("thoriumGameView") : "covers"
        homeViewMode = api.memory.has("thoriumHomeView") ?
                api.memory.get("thoriumHomeView") : "covers"
        if (rememberedPage === "games") {
            activeSystemIndex = systemRail.currentIndex
            allSystemsActive = activeSystemIndex === 0
            activeCollection = allSystemsActive ? null : collectionAtSystem(activeSystemIndex)
            activateCachedSystemSort()
            page = "games"
        } else {
            page = "home"
            homeZone = 0
        }
        initializeHardwarePhotos()
        updateClock()
        previewReady = true
        var returningFromGame = api.memory.has("parallaxReturningFromGame") &&
                Boolean(api.memory.get("parallaxReturningFromGame"))
        api.memory.set("parallaxReturningFromGame", false)
        if (!returningFromGame)
            applyConfiguredStartView()
        if (!returningFromGame)
            Qt.callLater(function() { root.startImportScan() })
        else
            Qt.callLater(function() { root.pollImportStatus() })
        Qt.callLater(function() {
            if (root.page === "games") {
                gameRail.currentIndex = Math.max(0,
                        Math.min(activeGameCount - 1, rememberedGame))
                if (root.gameViewMode === "list")
                    root.positionGameListAtIndex(gameRail.currentIndex)
                else
                    gameRail.positionViewAtIndex(gameRail.currentIndex, ListView.Center)
                root.activateGamePreview()
            } else {
                if (root.homeViewMode === "list") {
                    root.homeListCategory = 1
                    root.rebuildHomeList()
                } else {
                    systemRail.positionViewAtIndex(systemRail.currentIndex, ListView.Center)
                    root.activateHomePreview(true)
                }
            }
        })
        root.forceActiveFocus()
    }

    Keys.onPressed: {
        if (updatePromptOpen) {
            if (event.key === Qt.Key_Left || event.key === Qt.Key_Right ||
                    event.key === Qt.Key_Up || event.key === Qt.Key_Down)
                updatePromptChoice = updatePromptChoice === 0 ? 1 : 0
            else if (api.keys.isAccept(event)) {
                if (updatePromptChoice === 0) installReadyUpdate()
                else dismissReadyUpdate()
            } else if (api.keys.isCancel(event) || event.key === Qt.Key_Back ||
                       event.key === Qt.Key_Escape) {
                dismissReadyUpdate()
            }
            event.accepted = true
        } else if (aboutOpen) {
            if (api.keys.isAccept(event) || api.keys.isCancel(event) ||
                    event.key === Qt.Key_Back || event.key === Qt.Key_Escape ||
                    api.keys.isDetails(event))
                aboutOpen = false
            event.accepted = true
        } else if (accentEditorOpen) {
            if (api.keys.isCancel(event) || event.key === Qt.Key_Back ||
                    event.key === Qt.Key_Escape) {
                accentEditorOpen = false
            } else if (event.key === Qt.Key_Up) {
                accentEditorChannel = Math.max(0, accentEditorChannel - 1)
            } else if (event.key === Qt.Key_Down) {
                accentEditorChannel = Math.min(3, accentEditorChannel + 1)
            } else if (event.key === Qt.Key_Left) {
                adjustAccentEditor(-1)
            } else if (event.key === Qt.Key_Right) {
                adjustAccentEditor(1)
            } else if (api.keys.isFilters(event)) {
                resetAccentEditorColor()
            } else if (api.keys.isAccept(event)) {
                storeAccentEditorColor()
                accentEditorOpen = false
            }
            event.accepted = true
        } else if (gameActionOpen) {
            if (gameActionMode === "rename" && renameField.activeFocus) {
                if (api.keys.isCancel(event) || event.key === Qt.Key_Back || event.key === Qt.Key_Escape) {
                    Qt.inputMethod.reset()
                    Qt.inputMethod.hide()
                    renameField.focus = false
                    gameActionMode = "menu"
                    root.forceActiveFocus()
                }
                event.accepted = true
            } else if (gameActionMode === "menu") {
                if (api.keys.isCancel(event) || event.key === Qt.Key_Back || event.key === Qt.Key_Escape) {
                    closeGameActions()
                } else if (event.key === Qt.Key_Up || event.key === Qt.Key_Down ||
                           event.key === Qt.Key_Left || event.key === Qt.Key_Right) {
                    moveGameActionSelection(
                                event.key === Qt.Key_Up || event.key === Qt.Key_Left ? -1 : 1)
                } else if (api.keys.isAccept(event)) {
                    if (gameActionIndex === 0) beginRenameGame()
                    else if (gameActionAllowsRemoveFromList() && gameActionIndex === 1)
                        gameActionMode = "confirm-remove"
                    else gameActionMode = "confirm-delete"
                }
                event.accepted = true
            } else if (gameActionMode === "confirm-remove") {
                if (api.keys.isCancel(event) || event.key === Qt.Key_Back || event.key === Qt.Key_Escape) gameActionMode = "menu"
                else if (api.keys.isAccept(event)) submitRemoveFromList()
                event.accepted = true
            } else if (gameActionMode === "confirm-delete") {
                if (api.keys.isCancel(event) || event.key === Qt.Key_Back || event.key === Qt.Key_Escape) gameActionMode = "menu"
                else if (api.keys.isAccept(event)) submitDeleteGame()
                event.accepted = true
            } else if (gameActionMode !== "working") {
                if (api.keys.isAccept(event) || api.keys.isCancel(event) || event.key === Qt.Key_Back || event.key === Qt.Key_Escape) closeGameActions()
                event.accepted = true
            } else {
                event.accepted = true
            }
        } else if (searchField.activeFocus) {
            // Text and IME key events must never fall through to Pegasus's
            // controller aliases (for example, the letter A as Accept).
            if (event.nativeScanCode === 305 || event.key === Qt.Key_Back ||
                    event.key === Qt.Key_Escape) {
                endSearch()
                event.accepted = true
            } else {
                event.accepted = false
            }
        } else if (searchOpen && (api.keys.isCancel(event) ||
                                  event.key === Qt.Key_Back ||
                                  event.key === Qt.Key_Escape)) {
            endSearch()
            event.accepted = true
        } else if (coverOrderEditorOpen) {
            if (api.keys.isCancel(event) || event.key === Qt.Key_Back ||
                    event.key === Qt.Key_Escape || api.keys.isDetails(event)) {
                coverOrderEditorOpen = false
                settingsOpen = true
            } else if (event.key === Qt.Key_Up) {
                coverOrderEditorIndex = Math.max(0, coverOrderEditorIndex - 1)
            } else if (event.key === Qt.Key_Down) {
                coverOrderEditorIndex = Math.min(coverRowOrder.length - 1,
                                                 coverOrderEditorIndex + 1)
            } else if (event.key === Qt.Key_Left) {
                moveCoverOrderItem(-1)
            } else if (event.key === Qt.Key_Right || api.keys.isAccept(event)) {
                moveCoverOrderItem(1)
            }
            event.accepted = true
        } else if (settingsOpen) {
            if (api.keys.isCancel(event) || event.key === Qt.Key_Back ||
                    event.key === Qt.Key_Escape || api.keys.isDetails(event)) {
                settingsOpen = false
            } else if (event.key === Qt.Key_Up) {
                settingsIndex = Math.max(0, settingsIndex - 1)
            } else if (event.key === Qt.Key_Down) {
                settingsIndex = Math.min(settingsOptionCount - 1,
                                         settingsIndex + 1)
            } else if (event.key === Qt.Key_Left) {
                activateSetting(-1)
            } else if (event.key === Qt.Key_Right) {
                activateSetting(1)
            } else if (api.keys.isAccept(event)) {
                activateSetting(0)
            }
            event.accepted = true
        } else if (handleRightStickViewKey(event)) {
            event.accepted = true
        } else if (page === "home") {
            if (api.keys.isDetails(event)) {
                settingsOpen = true
                settingsIndex = 0
                event.accepted = true
            } else if (api.keys.isFilters(event)) {
                toggleHomeView()
                event.accepted = true
            } else if (homeViewMode === "list") {
                var homeSystemDirection = triggerDirection(event)
                if (api.keys.isCancel(event) || event.key === Qt.Key_Back ||
                        event.key === Qt.Key_Escape) {
                    if (homeListFocusColumn === 1) {
                        homeListFocusColumn = 0
                        rebuildHomeList()
                    }
                } else if (homeSystemDirection !== 0) {
                    rememberHandledTrigger(homeSystemDirection)
                    stepSystem(homeSystemDirection)
                } else if (api.keys.isPrevPage(event)) {
                    cycleHomeListCategory(-1)
                } else if (api.keys.isNextPage(event)) {
                    cycleHomeListCategory(1)
                } else if (event.key === Qt.Key_Left) {
                    stepHomeListPage(-1)
                } else if (event.key === Qt.Key_Right) {
                    stepHomeListPage(1)
                } else if (event.key === Qt.Key_Up) {
                    if (homeListFocusColumn === 0)
                        stepSystem(-1)
                    else if (homeListEntries.length > 0)
                        homeListRail.decrementCurrentIndex()
                } else if (event.key === Qt.Key_Down) {
                    if (homeListFocusColumn === 0)
                        stepSystem(1)
                    else if (homeListEntries.length > 0)
                        homeListRail.incrementCurrentIndex()
                } else if (api.keys.isAccept(event)) {
                    if (homeListFocusColumn === 0)
                        enterHomeListGames()
                    else
                        launch(activeGame)
                }
                event.accepted = true
            } else {
                var coverSystemDirection = triggerDirection(event)
                if (coverSystemDirection !== 0) {
                    rememberHandledTrigger(coverSystemDirection)
                    stepSystem(coverSystemDirection)
                    event.accepted = true
                } else if (api.keys.isPrevPage(event)) {
                    stepCoverZone(-1)
                    event.accepted = true
                } else if (api.keys.isNextPage(event)) {
                    stepCoverZone(1)
                    event.accepted = true
                } else if (event.key === Qt.Key_Up) {
                stepCoverZone(-1)
                event.accepted = true
                } else if (event.key === Qt.Key_Down) {
                stepCoverZone(1)
                event.accepted = true
                } else if (event.key === Qt.Key_Left) {
                if (homeZone === 0)
                    root.stepSystem(-1)
                else
                    root.stepHomeShelf(-1)
                event.accepted = true
                } else if (event.key === Qt.Key_Right) {
                if (homeZone === 0)
                    root.stepSystem(1)
                else
                    root.stepHomeShelf(1)
                event.accepted = true
                } else if (api.keys.isAccept(event)) {
                if (homeZone === 0)
                    enterCollection()
                else
                    launch(homeShelfGame(homeZone))
                event.accepted = true
                }
            }
        } else {
            var openSystemDirection = triggerDirection(event)
            // Raw Thor trigger identity must win over every generic Pegasus
            // alias. On the first press after reversing L2/R2, this Qt build
            // can briefly retain the previous semantic alias; checking
            // Cancel/PageUp first swallowed that otherwise valid scan code.
            if (api.keys.isDetails(event)) {
                settingsOpen = true
                settingsIndex = 0
                event.accepted = true
            } else if (api.keys.isFilters(event)) {
                toggleGameView()
                event.accepted = true
            } else if (openSystemDirection !== 0) {
                rememberHandledTrigger(openSystemDirection)
                stepOpenSystem(openSystemDirection)
                event.accepted = true
            } else if (api.keys.isCancel(event) || event.key === Qt.Key_Back ||
                       event.key === Qt.Key_Escape) {
                returnHome()
                event.accepted = true
            } else if (event.key === 1048576 &&
                       (api.keys.isPageUp(event) || api.keys.isPageDown(event))) {
                // Thor's Qt input plugin reports the same generic pressed key
                // for both triggers and can retain the previous direction's
                // semantic alias. Consume that ambiguous edge; the released
                // edge below carries the correct physical identity.
                event.accepted = true
            } else if (api.keys.isPageUp(event)) {
                stepOpenSystem(-1)
                event.accepted = true
            } else if (api.keys.isPageDown(event)) {
                stepOpenSystem(1)
                event.accepted = true
            } else if (api.keys.isPrevPage(event)) {
                cycleSort(-1)
                event.accepted = true
            } else if (api.keys.isNextPage(event)) {
                cycleSort(1)
                event.accepted = true
            } else if (event.key === Qt.Key_Left) {
                if (gameViewMode === "list")
                    gameRail.currentIndex = Math.max(0, gameRail.currentIndex - 8)
                else
                    gameRail.decrementCurrentIndex()
                event.accepted = true
            } else if (event.key === Qt.Key_Right) {
                if (gameViewMode === "list")
                    gameRail.currentIndex = Math.min(gameRail.count - 1, gameRail.currentIndex + 8)
                else
                    gameRail.incrementCurrentIndex()
                event.accepted = true
            } else if (event.key === Qt.Key_Up) {
                if (gameViewMode === "list")
                    gameRail.decrementCurrentIndex()
                else
                    gameRail.currentIndex = Math.max(0, gameRail.currentIndex - 6)
                event.accepted = true
            } else if (event.key === Qt.Key_Down) {
                if (gameViewMode === "list")
                    gameRail.incrementCurrentIndex()
                else
                    gameRail.currentIndex = Math.min(gameRail.count - 1, gameRail.currentIndex + 6)
                event.accepted = true
            } else if (api.keys.isAccept(event)) {
                launch(activeGame)
                event.accepted = true
            }
        }
    }

    Keys.onReleased: {
        if (searchField.activeFocus || root.settingsOpen ||
                (root.page !== "games" && root.page !== "home"))
            return
        var direction = root.triggerDirection(event)
        if (direction < 0) {
            if (!root.leftTriggerPressHandled)
                root.page === "games" ? root.stepOpenSystem(-1) : root.stepSystem(-1)
            root.leftTriggerPressHandled = false
            event.accepted = true
        } else if (direction > 0) {
            if (!root.rightTriggerPressHandled)
                root.page === "games" ? root.stepOpenSystem(1) : root.stepSystem(1)
            root.rightTriggerPressHandled = false
            event.accepted = true
        }
    }

    Timer {
        id: importPollTimer
        interval: 1200
        running: true
        repeat: true
        onTriggered: root.pollImportStatus()
    }

    Timer {
        id: importToastDismiss
        interval: root.importState === "error" ? 12000 :
                  (root.importAdded > 0 || root.importNeedsReload ? 7000 : 4200)
        repeat: false
        onTriggered: root.importToastVisible = false
    }

    Timer {
        id: importedLibraryReload
        interval: 900
        repeat: false
        onTriggered: root.requestPreviewEndpoint("import/reload")
    }

    Rectangle {
        id: importToast
        z: 300
        anchors.top: parent.top
        anchors.right: parent.right
        anchors.topMargin: 108
        anchors.rightMargin: 46
        width: 430
        height: root.importDetail !== "" ? 148 : (root.importIdentified > 0 ? 126 : 96)
        visible: root.importToastVisible
        color: "#ee0b0f16"
        border.width: 1
        border.color: root.importState === "error" ? "#ff6d70" : root.accent
        radius: 4

        Rectangle {
            x: 16
            y: 18
            width: 4
            height: 36
            color: root.importState === "error" ? "#ff6d70" : root.accent
        }

        Text {
            x: 34
            y: 15
            width: parent.width - 50
            text: root.importState === "complete" ? "LIBRARY UPDATE COMPLETE" :
                  root.importState === "error" ? "LIBRARY UPDATE PAUSED" :
                  "UPDATING GAME LIBRARY"
            color: "#f2f4f8"
            font.family: global.fonts.condensed
            font.pixelSize: 16
            font.weight: Font.Bold
            font.letterSpacing: 1.6
        }

        Text {
            x: 34
            y: 42
            width: parent.width - 50
            text: root.importMessage
            color: "#aeb7c8"
            elide: Text.ElideRight
            font.family: global.fonts.sans
            font.pixelSize: 12
        }

        Text {
            x: 34
            y: 66
            width: parent.width - 50
            visible: root.importDetail === "" && root.importIdentified > 0
            text: {
                var shown = root.importTitles.slice(0, 2).join("  •  ")
                if (root.importTitles.length > 2)
                    shown += "  •  +" + (root.importTitles.length - 2) + " more"
                return shown
            }
            color: root.accent
            elide: Text.ElideRight
            font.family: global.fonts.sans
            font.pixelSize: 11
            font.weight: Font.DemiBold
        }

        Text {
            x: 34
            y: 68
            width: parent.width - 50
            visible: root.importDetail !== ""
            text: root.importDetail
            color: root.accent
            elide: Text.ElideMiddle
            font.family: global.fonts.sans
            font.pixelSize: 11
            font.weight: Font.DemiBold
        }

        Text {
            x: 34
            y: 91
            width: parent.width - 50
            visible: root.importDetail !== "" && root.importTotal > 0
            text: root.importCurrent + " OF " + root.importTotal
            color: "#aeb7c8"
            font.family: global.fonts.sans
            font.pixelSize: 10
            font.letterSpacing: 1.2
        }

        Rectangle {
            x: 34
            y: parent.height - 18
            width: parent.width - 50
            height: 3
            color: "#26303e"

            Rectangle {
                width: parent.width * Math.max(0, Math.min(1, root.importProgress))
                height: parent.height
                color: root.importState === "error" ? "#ff6d70" : root.accent
                Behavior on width { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
            }
        }
    }

    Timer {
        interval: 700
        repeat: true
        running: true
        triggeredOnStart: true
        onTriggered: {
            var applicationActive = Qt.application.state === Qt.ApplicationActive
            if (applicationActive) {
                if (!root.applicationWasActive && root.previewReady) {
                    // Rebuild the complete warm set after launch/resume. A
                    // current-only refresh would evict the preloaded neighbors
                    // and reintroduce a black frame on the very first move.
                    if (root.page === "home" && root.homeViewMode === "list")
                        root.activateHomeListPreview()
                    else if (root.page === "home" && root.homeZone === 0)
                        root.activateHomePreview(false)
                    else if (root.page === "games")
                        root.activateGamePreview()
                    else
                        root.activateShelfPreview(root.homeZone)
                }
                root.sendPreviewHeartbeat()
            }
            root.applicationWasActive = applicationActive
        }
    }

    Timer {
        interval: 180
        repeat: true
        running: true
        onTriggered: root.pollBottomLaunchRequest()
    }

    Timer {
        interval: 30000
        repeat: true
        running: true
        onTriggered: root.updateClock()
    }

    Timer {
        interval: 1800
        repeat: true
        running: true
        triggeredOnStart: true
        onTriggered: root.pollUpdateStatus()
    }

    Timer {
        id: libraryIndexRetry
        interval: 900
        repeat: false
        onTriggered: root.loadLibraryIndex()
    }

    Timer {
        id: libraryIndexBuildTimer
        interval: 1
        repeat: false
        onTriggered: root.continueLibraryIndexBuild()
    }

    Timer {
        id: systemLedCommit
        interval: 12
        repeat: false
        onTriggered: root.applySystemLedColor()
    }

    // Stock Thor controls can reapply their static LED color after focus or
    // power-state changes. Reassert the selected system color at low frequency;
    // the companion preserves any externally changed AYN brightness value.
    Timer {
        interval: 750
        repeat: true
        running: root.systemLedEnabled
        onTriggered: root.applySystemLedColor()
    }

    Timer {
        id: navigationPersistence
        interval: 220
        repeat: false
        onTriggered: root.flushNavigationPersistence()
    }

    // Keep preview selection out of the D-pad event turn. Fast presses update
    // the rail immediately and only the settled selection starts a movie.
    Timer {
        id: systemChangeCommit
        interval: 24
        repeat: false
        onTriggered: {
            if (root.page !== "home" || root.homeZone !== 0)
                return
            root.activateHomePreview(false)
        }
    }

    Timer {
        id: homeListRebuild
        interval: 1
        repeat: false
        onTriggered: root.rebuildHomeList()
    }

    Timer {
        id: sortChangeCommit
        interval: 1
        repeat: false
        onTriggered: {
            if (root.page !== "games")
                return
            gameRail.currentIndex = 0
            if (root.gameViewMode === "list")
                gameListRail.positionViewAtIndex(0, ListView.Beginning)
            else
                gameRail.positionViewAtIndex(0, ListView.Beginning)
            root.activateGamePreview()
            root.forceActiveFocus()
        }
    }

    Timer {
        id: searchChangeCommit
        interval: 80
        repeat: false
        onTriggered: {
            if (root.page !== "games")
                return
            gameRail.currentIndex = 0
            if (activeGameCount > 0) {
                if (root.gameViewMode === "list")
                    gameListRail.positionViewAtIndex(0, ListView.Beginning)
                else
                    gameRail.positionViewAtIndex(0, ListView.Beginning)
            }
            root.activateGamePreview()
        }
    }

    // A collection source changes synchronously, but its native sorted proxy
    // settles on the next event-loop turn. Coalesce rapid L2/R2 presses and
    // submit the lower-screen selection only after the final platform's row 0
    // exists. This prevents a one-request flash from the departed system.
    Timer {
        id: systemOpenCommit
        interval: 1
        repeat: false
        onTriggered: {
            if (root.page !== "games")
                return
            gameRail.currentIndex = 0
            if (root.gameViewMode === "list")
                gameListRail.positionViewAtIndex(0, ListView.Beginning)
            else
                gameRail.positionViewAtIndex(0, ListView.Beginning)
            root.activateGamePreview()
            root.forceActiveFocus()
        }
    }

    // Once the user settles on a new system, quietly replace the system they
    // just left with another random preview. Fast backtracking stays instant;
    // a later revisit gets a different game instead of item zero every time.
    Timer {
        id: rerollDepartedPreview
        interval: 700
        repeat: false
        onTriggered: {
            var departed = root.departedSystemIndex
            if (departed < 0 || root.page !== "home")
                return
            var oldGame = root.lastHomeGameBySystem[departed] || null
            var replacement = root.randomHomeVideoGame(departed, oldGame)
            root.lastHomeGameBySystem[departed] = replacement
            var slot = root.slotFor("home", departed, -1)
            if (slot && slot !== root.activePreviewSlot)
                root.assignSlot(slot, "home", departed, -1, replacement)
            root.departedSystemIndex = -1
            // The slot above is already refreshed. Do not re-run the current
            // selection and HTTP update merely because a neighbor rerolled.
        }
    }

    ListModel {
        id: systemModel
        // All Systems is always present. Physical system cards are appended
        // from the catalog only when Pegasus actually found ROMs for them.
        ListElement { name: "ALL SYSTEMS"; years: "FULL LIBRARY"; mark: "ALL"; collectionName: ""; folder: "all"; family: ""; accent: "#dce4f2" }
    }

    ListModel {
        id: systemCatalog
        // Chronological by first retail release. Years describe the primary
        // hardware/product lifecycle rather than online-service availability.
        ListElement { name: "ALL SYSTEMS"; years: "FULL LIBRARY"; mark: "ALL"; collectionName: ""; folder: "all"; family: ""; accent: "#dce4f2" }
        ListElement { name: "ARCADE"; years: "1971–PRESENT"; mark: "AR"; collectionName: "Arcade"; folder: "arcade"; family: "arcade"; accent: "#35d0e6" }
        ListElement { name: "APPLE II"; years: "1977–1993"; mark: "AII"; collectionName: "Apple II"; folder: "apple2"; family: "apple"; accent: "#a8b2c1" }
        ListElement { name: "ATARI 2600"; years: "1977–1992"; mark: "2600"; collectionName: "Atari 2600"; folder: "atari2600"; family: "atari"; accent: "#ff5964" }
        ListElement { name: "MAGNAVOX ODYSSEY²"; years: "1978–1984"; mark: "O²"; collectionName: "Magnavox Odyssey²"; folder: "odyssey2"; family: "magnavox"; accent: "#c7f464" }
        ListElement { name: "MATTEL INTELLIVISION"; years: "1979–1990"; mark: "INTV"; collectionName: "Mattel Intellivision"; folder: "intellivision"; family: "mattel"; accent: "#48cae4" }
        ListElement { name: "ATARI 8-BIT"; years: "1979–1992"; mark: "A8"; collectionName: "Atari 8-bit"; folder: "atari800"; family: "atari"; accent: "#ff5964" }
        ListElement { name: "DOS"; years: "1981–2000"; mark: "DOS"; collectionName: "DOS"; folder: "dos"; family: "microsoft"; accent: "#4aa3ff" }
        ListElement { name: "ATARI 5200"; years: "1982–1984"; mark: "5200"; collectionName: "Atari 5200"; folder: "atari5200"; family: "atari"; accent: "#ff5964" }
        ListElement { name: "COLECOVISION"; years: "1982–1985"; mark: "CV"; collectionName: "ColecoVision"; folder: "colecovision"; family: "coleco"; accent: "#f72585" }
        ListElement { name: "COMMODORE 64"; years: "1982–1994"; mark: "C64"; collectionName: "Commodore 64"; folder: "c64"; family: "commodore"; accent: "#23c9b8" }
        ListElement { name: "ZX SPECTRUM"; years: "1982–1992"; mark: "ZX"; collectionName: "Sinclair ZX Spectrum"; folder: "zxspectrum"; family: "sinclair"; accent: "#e63946" }
        ListElement { name: "NINTENDO ENTERTAINMENT SYSTEM"; years: "1983–2003"; mark: "NES"; collectionName: "Nintendo Entertainment System"; folder: "nes"; family: "nintendo"; accent: "#ff9f43" }
        ListElement { name: "SEGA SG-1000"; years: "1983–1985"; mark: "SG"; collectionName: "Sega SG-1000"; folder: "sg1000"; family: "sega"; accent: "#4fd17f" }
        ListElement { name: "MSX"; years: "1983–1995"; mark: "MSX"; collectionName: "Microsoft MSX"; folder: "msx"; family: "microsoft"; accent: "#4aa3ff" }
        ListElement { name: "AMSTRAD CPC"; years: "1984–1990"; mark: "CPC"; collectionName: "Amstrad CPC"; folder: "amstradcpc"; family: "amstrad"; accent: "#00b4d8" }
        ListElement { name: "COMMODORE AMIGA"; years: "1985–1996"; mark: "AMIGA"; collectionName: "Commodore Amiga"; folder: "amiga"; family: "commodore"; accent: "#23c9b8" }
        ListElement { name: "ATARI ST"; years: "1985–1993"; mark: "ST"; collectionName: "Atari ST"; folder: "atarist"; family: "atari"; accent: "#ff5964" }
        ListElement { name: "SEGA MASTER SYSTEM"; years: "1985–1996"; mark: "SMS"; collectionName: "Sega Master System"; folder: "mastersystem"; family: "sega"; accent: "#4fd17f" }
        ListElement { name: "ATARI 7800"; years: "1986–1992"; mark: "7800"; collectionName: "Atari 7800"; folder: "atari7800"; family: "atari"; accent: "#ff5964" }
        ListElement { name: "NEC PC ENGINE"; years: "1987–1994"; mark: "PCE"; collectionName: "NEC PC Engine"; folder: "pcengine"; family: "nec"; accent: "#ef6cff" }
        ListElement { name: "NEC PC ENGINE CD"; years: "1988–1999"; mark: "PCECD"; collectionName: "NEC PC Engine CD"; folder: "pcenginecd"; family: "nec"; accent: "#ef6cff" }
        ListElement { name: "SEGA GENESIS"; years: "1988–1997"; mark: "GEN"; collectionName: "Sega Genesis"; folder: "megadrive"; family: "sega"; accent: "#4fd17f" }
        ListElement { name: "GAME BOY"; years: "1989–2003"; mark: "GB"; collectionName: "Nintendo Game Boy"; folder: "gb"; family: "nintendo"; accent: "#ff9f43" }
        ListElement { name: "SEGA GAME GEAR"; years: "1990–1997"; mark: "GG"; collectionName: "Sega Game Gear"; folder: "gamegear"; family: "sega"; accent: "#4fd17f" }
        ListElement { name: "SNK NEO GEO"; years: "1990–2004"; mark: "NEO"; collectionName: "SNK Neo Geo"; folder: "neogeo"; family: "snk"; accent: "#ffd166" }
        ListElement { name: "SUPER NINTENDO"; years: "1990–2003"; mark: "SNES"; collectionName: "Super Nintendo Entertainment System"; folder: "snes"; family: "nintendo"; accent: "#ff9f43" }
        ListElement { name: "SEGA CD"; years: "1991–1996"; mark: "SCD"; collectionName: "Sega CD"; folder: "segacd"; family: "sega"; accent: "#4fd17f" }
        ListElement { name: "3DO"; years: "1993–1996"; mark: "3DO"; collectionName: "3DO Interactive Multiplayer"; folder: "3do"; family: "3do"; accent: "#b8f2e6" }
        ListElement { name: "AMIGA CD32"; years: "1993–1994"; mark: "CD32"; collectionName: "Commodore Amiga CD32"; folder: "amigacd32"; family: "commodore"; accent: "#23c9b8" }
        ListElement { name: "ATARI JAGUAR"; years: "1993–1996"; mark: "JAG"; collectionName: "Atari Jaguar"; folder: "jaguar"; family: "atari"; accent: "#ff5964" }
        ListElement { name: "SEGA 32X"; years: "1994–1996"; mark: "32X"; collectionName: "Sega 32X"; folder: "sega32x"; family: "sega"; accent: "#4fd17f" }
        ListElement { name: "SEGA SATURN"; years: "1994–2000"; mark: "SAT"; collectionName: "Sega Saturn"; folder: "saturn"; family: "sega"; accent: "#4fd17f" }
        ListElement { name: "PLAYSTATION"; years: "1994–2006"; mark: "PS1"; collectionName: "Sony PlayStation"; folder: "psx"; family: "sony"; accent: "#a987ff" }
        ListElement { name: "NEO GEO CD"; years: "1994–1997"; mark: "NGCD"; collectionName: "SNK Neo Geo CD"; folder: "neogeocd"; family: "snk"; accent: "#ffd166" }
        ListElement { name: "VIRTUAL BOY"; years: "1995–1996"; mark: "VB"; collectionName: "Nintendo Virtual Boy"; folder: "virtualboy"; family: "nintendo"; accent: "#ff9f43" }
        ListElement { name: "NINTENDO 64"; years: "1996–2002"; mark: "N64"; collectionName: "Nintendo 64"; folder: "n64"; family: "nintendo"; accent: "#ff9f43" }
        ListElement { name: "SEGA DREAMCAST"; years: "1998–2001"; mark: "DC"; collectionName: "Sega Dreamcast"; folder: "dreamcast"; family: "sega"; accent: "#4fd17f" }
        ListElement { name: "GAME BOY COLOR"; years: "1998–2003"; mark: "GBC"; collectionName: "Nintendo Game Boy Color"; folder: "gbc"; family: "nintendo"; accent: "#ff9f43" }
        ListElement { name: "NEO GEO POCKET"; years: "1998–1999"; mark: "NGP"; collectionName: "SNK Neo Geo Pocket"; folder: "ngp"; family: "snk"; accent: "#ffd166" }
        ListElement { name: "BANDAI WONDERSWAN"; years: "1999–2003"; mark: "WS"; collectionName: "Bandai WonderSwan"; folder: "wonderswan"; family: "bandai"; accent: "#ff7d3a" }
        ListElement { name: "PLAYSTATION 2"; years: "2000–2013"; mark: "PS2"; collectionName: "Sony PlayStation 2"; folder: "ps2"; family: "sony"; accent: "#a987ff" }
        ListElement { name: "WONDERSWAN COLOR"; years: "2000–2003"; mark: "WSC"; collectionName: "Bandai WonderSwan Color"; folder: "wonderswancolor"; family: "bandai"; accent: "#ff7d3a" }
        ListElement { name: "GAME BOY ADVANCE"; years: "2001–2010"; mark: "GBA"; collectionName: "Nintendo Game Boy Advance"; folder: "gba"; family: "nintendo"; accent: "#ff9f43" }
        ListElement { name: "NINTENDO GAMECUBE"; years: "2001–2007"; mark: "GC"; collectionName: "Nintendo GameCube"; folder: "gc"; family: "nintendo"; accent: "#ff9f43" }
        ListElement { name: "MICROSOFT XBOX"; years: "2001–2009"; mark: "XBOX"; collectionName: "Microsoft Xbox"; folder: "xbox"; family: "microsoft"; accent: "#4aa3ff" }
        ListElement { name: "NINTENDO DS"; years: "2004–2014"; mark: "NDS"; collectionName: "Nintendo DS"; folder: "nds"; family: "nintendo"; accent: "#ff9f43" }
        ListElement { name: "PLAYSTATION PORTABLE"; years: "2004–2014"; mark: "PSP"; collectionName: "Sony PlayStation Portable"; folder: "psp"; family: "sony"; accent: "#a987ff" }
        ListElement { name: "XBOX 360"; years: "2005–2016"; mark: "X360"; collectionName: "Microsoft Xbox 360"; folder: "xbox360"; family: "microsoft"; accent: "#4aa3ff" }
        ListElement { name: "PLAYSTATION 3"; years: "2006–2017"; mark: "PS3"; collectionName: "Sony PlayStation 3"; folder: "ps3"; family: "sony"; accent: "#a987ff" }
        ListElement { name: "NINTENDO WII"; years: "2006–2017"; mark: "WII"; collectionName: "Nintendo Wii"; folder: "wii"; family: "nintendo"; accent: "#ff9f43" }
        ListElement { name: "NINTENDO 3DS"; years: "2011–2020"; mark: "3DS"; collectionName: "Nintendo 3DS"; folder: "n3ds"; family: "nintendo"; accent: "#ff9f43" }
        ListElement { name: "PLAYSTATION VITA"; years: "2011–2019"; mark: "VITA"; collectionName: "Sony PlayStation Vita"; folder: "psvita"; family: "sony"; accent: "#a987ff" }
        ListElement { name: "NINTENDO WII U"; years: "2012–2017"; mark: "WIIU"; collectionName: "Nintendo Wii U"; folder: "wiiu"; family: "nintendo"; accent: "#ff9f43" }
        ListElement { name: "WINDOWS"; years: "1985–PRESENT"; mark: "WIN"; collectionName: "Microsoft Windows"; folder: "windows"; family: "microsoft"; accent: "#4aa3ff" }
        ListElement { name: "NINTENDO SWITCH"; years: "2017–PRESENT"; mark: "NSW"; collectionName: "Nintendo Switch"; folder: "switch"; family: "nintendo"; accent: "#ff9f43" }
    }

    // Predecode official platform logotypes used by the rail. Full-resolution system
    // wallpapers are held by the persistent layers below.
    Repeater {
        model: systemModel.count
        Item {
            x: -2000
            y: -2000
            width: 1
            height: 1
            opacity: 0

            Image {
                source: systemModel.get(index).folder === "all" ?
                        Qt.resolvedUrl("assets/hardware-cutouts/all/0.png") :
                        Qt.resolvedUrl("assets/logos-png/" +
                                       systemModel.get(index).folder + ".png")
                // These are packaged local assets and the system model is
                // intentionally small. Decode once at startup so delegate
                // recycling can never produce a blank logo frame.
                asynchronous: false
                cache: true
                sourceSize.width: 700
                sourceSize.height: 300
            }
        }
    }

    // The header uses platform-family marks rather than console marks. Decode
    // those independently as well; preloading only logos-png left a visible
    // blank frame the first time Nintendo/Sega/Sony/Microsoft appeared.
    Repeater {
        model: root.availableBrandSlugs
        Image {
            x: -2000
            y: -2000
            width: 1
            height: 1
            opacity: 0
            source: modelData === "arcade" ?
                    Qt.resolvedUrl("assets/logos-png/arcade.png") :
                    Qt.resolvedUrl("assets/brands/" + modelData + ".png")
            asynchronous: false
            cache: true
            sourceSize.width: 760
            sourceSize.height: 224
        }
    }

    SortFilterProxyModel {
        id: recentModel
        sourceModel: api.allGames
        sorters: RoleSorter { roleName: "lastPlayed"; sortOrder: Qt.DescendingOrder }
        filters: [
            RangeFilter { roleName: "playCount"; minimumValue: 1 },
            ExpressionFilter {
                expression: root.isLucentLibraryGame(api.allGames.get(index)) &&
                            root.gameVisibleAfterMutation(api.allGames.get(index)) &&
                            root.gameVisibleInHomeCategory(api.allGames.get(index), 1)
            }
        ]
    }

    SortFilterProxyModel {
        id: mostPlayedModel
        sourceModel: api.allGames
        sorters: [
            RoleSorter { roleName: "playCount"; sortOrder: Qt.DescendingOrder },
            RoleSorter { roleName: "lastPlayed"; sortOrder: Qt.DescendingOrder }
        ]
        filters: [
            RangeFilter { roleName: "playCount"; minimumValue: 1 },
            ExpressionFilter {
                expression: root.isLucentLibraryGame(api.allGames.get(index)) &&
                            root.gameVisibleAfterMutation(api.allGames.get(index)) &&
                            root.gameVisibleInHomeCategory(api.allGames.get(index), 2)
            }
        ]
    }

    // Pegasus has no built-in "date added" role. Lucent writes an immutable
    // x-added-at timestamp when a ROM first enters its registry, then builds a
    // compact source-index rail here. Existing registry rows are backfilled
    // from the ROM mtime during the companion update.
    ListModel {
        id: recentlyAddedModel
    }

    // Filter the aggregate library only once, then keep four native sort
    // indexes over that shared base. This avoids four rounds of interpreted
    // visibility checks during startup while preserving instant sort swaps.
    SortFilterProxyModel {
        id: allLibraryFilterModel
        sourceModel: api.allGames
        filters: [
            ExpressionFilter {
                expression: root.isLucentLibraryGame(api.allGames.get(index)) &&
                            root.gameVisibleAfterMutation(api.allGames.get(index))
            }
        ]
    }

    SortFilterProxyModel {
        id: allCriticSortModel
        sourceModel: allLibraryFilterModel
        sorters: RoleSorter {
            roleName: "rating"
            sortOrder: Qt.DescendingOrder
        }
    }

    SortFilterProxyModel {
        id: allUserSortModel
        sourceModel: allLibraryFilterModel
        sorters: RoleSorter {
            roleName: "sortBy"
            sortOrder: Qt.AscendingOrder
        }
    }

    SortFilterProxyModel {
        id: allAlphaSortModel
        sourceModel: allLibraryFilterModel
        sorters: RoleSorter {
            roleName: "title"
            sortOrder: Qt.AscendingOrder
        }
    }

    SortFilterProxyModel {
        id: allReleaseSortModel
        sourceModel: allLibraryFilterModel
        sorters: RoleSorter {
            roleName: "release"
            sortOrder: Qt.DescendingOrder
        }
    }

    SortFilterProxyModel {
        id: systemGameSortModel
        // This proxy is only a startup fallback. Once the companion's native
        // per-system indexes are ready, leaving it attached made every L2/R2
        // move re-filter and re-sort the newly selected collection even
        // though the visible rails were already using activeSystemGames.
        // All Systems felt instant because it never triggered that hidden
        // rebuild. Detach here so every physical system swaps the same kind of
        // precomputed sorted index instead of doing duplicate work.
        sourceModel: !root.libraryIndexReady && root.searchQuery === "" &&
                     root.activeCollection ? root.activeCollection.games : null
        filters: [
            ExpressionFilter {
                expression: root.gameVisibleAfterMutation(
                    root.activeCollection ? root.activeCollection.games.get(index) : null)
            }
        ]
        sorters: RoleSorter {
            roleName: root.sortMode === "critic" ? "rating" :
                      root.sortMode === "user" ? "sortBy" :
                      root.sortMode === "release" ? "release" : "title"
            sortOrder: root.sortMode === "critic" || root.sortMode === "release" ?
                       Qt.DescendingOrder : Qt.AscendingOrder
        }
    }

    // Search is the only genuinely dynamic filter. Keep these proxies detached
    // during normal browsing so typing does not invalidate all warm caches.
    SortFilterProxyModel {
        id: allGamesSearchSortModel
        sourceModel: root.searchQuery !== "" ? api.allGames : null
        filters: [
            RegExpFilter {
                roleName: "title"
                pattern: root.escapedSearchPattern(root.searchQuery)
                caseSensitivity: Qt.CaseInsensitive
            },
            ExpressionFilter {
                expression: root.isLucentLibraryGame(api.allGames.get(index))
            },
            ExpressionFilter {
                expression: root.gameVisibleAfterMutation(api.allGames.get(index))
            }
        ]
        sorters: RoleSorter {
            roleName: root.sortMode === "critic" ? "rating" :
                      root.sortMode === "user" ? "sortBy" :
                      root.sortMode === "release" ? "release" : "title"
            sortOrder: root.sortMode === "critic" || root.sortMode === "release" ?
                       Qt.DescendingOrder : Qt.AscendingOrder
        }
    }

    SortFilterProxyModel {
        id: systemGameSearchSortModel
        sourceModel: root.searchQuery !== "" && root.activeCollection ?
                     root.activeCollection.games : null
        filters: [
            RegExpFilter {
                roleName: "title"
                pattern: root.escapedSearchPattern(root.searchQuery)
                caseSensitivity: Qt.CaseInsensitive
            },
            ExpressionFilter {
                expression: root.gameVisibleAfterMutation(
                    root.activeCollection ? root.activeCollection.games.get(index) : null)
            }
        ]
        sorters: RoleSorter {
            roleName: root.sortMode === "critic" ? "rating" :
                      root.sortMode === "user" ? "sortBy" :
                      root.sortMode === "release" ? "release" : "title"
            sortOrder: root.sortMode === "critic" || root.sortMode === "release" ?
                       Qt.DescendingOrder : Qt.AscendingOrder
        }
    }

    Component {
        id: homeShelfCard

        Item {
            id: shelfCard
            property var game: root.homeShelfGameAt(ListView.view.zone, index)
            property bool isSelected: ListView.isCurrentItem &&
                    root.homeZone === ListView.view.zone
            property var packageSize: root.packageDimensionsForGame(game)
            property real packageScale: root.coverShelfPixelsPerMillimetre()
            property real coverAreaHeight: Math.max(108, height -
                    (root.coverViewRowCount === 1 ? 128 : 98))
            property real desiredCoverWidth: packageSize.width * packageScale
            property real desiredCoverHeight: packageSize.height * packageScale
            property real maximumCoverWidth: root.coverViewRowCount === 1 ? 520 :
                    (root.coverViewRowCount === 2 ? 310 : 220)
            property real packageFit: Math.min(1,
                    coverAreaHeight / Math.max(1, desiredCoverHeight),
                    maximumCoverWidth / Math.max(1, desiredCoverWidth))
            property real coverWidth: desiredCoverWidth * packageFit
            property real coverHeight: desiredCoverHeight * packageFit
            width: Math.max(root.coverViewRowCount === 1 ? 180 :
                    (root.coverViewRowCount === 2 ? 150 : 130), coverWidth + 22)
            height: Math.max(184, root.coverRowSlotHeight - 52)
            // Selection is communicated by the accent frame, not by changing
            // package scale. That keeps every adjacent case in the same real-
            // world proportion even while focus moves across the shelf.
            scale: 1.0
            opacity: isSelected ? 1.0 : 0.84
            Behavior on scale { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
            Behavior on opacity { NumberAnimation { duration: 150 } }

            Rectangle {
                x: shelfCover.x - 7
                y: shelfCover.y - 7
                width: shelfCover.width + 14
                height: shelfCover.height + 14
                radius: 7
                color: shelfCard.isSelected ?
                       Qt.darker(root.accentForGame(game), 4.5) : "transparent"
                border.width: shelfCard.isSelected ? 5 : 0
                border.color: root.accentForGame(game)
            }

            Image {
                id: shelfCover
                x: (parent.width - parent.coverWidth) / 2
                y: 2 + (parent.coverAreaHeight - parent.coverHeight) / 2
                width: parent.coverWidth
                height: parent.coverHeight
                source: game ? (game.assets.boxFront || "") : ""
                fillMode: Image.PreserveAspectFit
                asynchronous: true
                cache: true
                smooth: true
                mipmap: true
            }

            Text {
                id: shelfTitle
                x: 8
                y: parent.coverAreaHeight + 10
                width: parent.width - 16
                height: root.coverViewRowCount === 1 ? 48 :
                        (root.coverViewRowCount === 2 ? 38 : 30)
                text: root.displayTitle(game)
                color: "#eef1f7"
                elide: Text.ElideRight
                wrapMode: Text.Wrap
                maximumLineCount: 2
                font.family: global.fonts.sans
                font.pixelSize: root.coverViewRowCount === 1 ? 22 :
                                (root.coverViewRowCount === 2 ? 18 : 15)
                font.weight: Font.Bold
                style: Text.Outline
                styleColor: "#d0000000"
            }

            Text {
                x: 8
                // Follow the title instead of hugging the bottom of the card.
                // This keeps ratings visually paired with the game name in
                // every Cover View shelf and at every configured row count.
                y: shelfTitle.y +
                   Math.min(shelfTitle.height, shelfTitle.paintedHeight) + 4
                width: parent.width - 16
                height: root.coverViewRowCount === 1 ? 34 : 28
                text: root.scoreText(game)
                color: root.accentForGame(game)
                wrapMode: Text.NoWrap
                maximumLineCount: 1
                fontSizeMode: Text.HorizontalFit
                minimumPixelSize: 11
                font.family: global.fonts.sans
                font.pixelSize: root.coverViewRowCount === 1 ? 16 :
                                (root.coverViewRowCount === 2 ? 14 : 11)
                font.weight: Font.Bold
                font.letterSpacing: 0
                style: Text.Outline
                styleColor: "#d0000000"
            }

            MouseArea {
                anchors.fill: parent
                pressAndHoldInterval: 800
                onPressAndHold: {
                    ListView.view.currentIndex = index
                    root.focusHomeZone(ListView.view.zone)
                    root.openGameActions(game, index)
                }
                onClicked: {
                    if (root.gameActionOpen) return
                    ListView.view.currentIndex = index
                    root.focusHomeZone(ListView.view.zone)
                    root.launch(game)
                }
            }
        }
    }

    Rectangle {
        anchors.fill: parent
        color: "#07090d"
    }

    // The home backdrop is code-native: no generated image can leak back in.
    // Real product photography sits above this restrained accent field.
    Rectangle {
        anchors.fill: parent
        visible: root.showSystemBackdrop
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#07090d" }
            GradientStop { position: 0.58; color: "#0a0e15" }
            GradientStop { position: 1.0; color: Qt.darker(root.accent, 4.2) }
        }
    }

    LinearGradient {
        anchors.fill: parent
        visible: root.showSystemBackdrop
        start: Qt.point(0, height * 0.50)
        end: Qt.point(width, height * 0.50)
        gradient: Gradient {
            GradientStop { position: 0.0; color: "transparent" }
            GradientStop {
                position: 0.38
                color: Qt.rgba(root.accent.r, root.accent.g, root.accent.b, 0.04)
            }
            GradientStop {
                position: 0.70
                color: Qt.rgba(root.accent.r, root.accent.g, root.accent.b, 0.15)
            }
            GradientStop {
                position: 1.0
                color: Qt.rgba(root.accent.r, root.accent.g, root.accent.b, 0.28)
            }
        }
    }

    // Each system owns a persistent, decoded layer. Its real product photo is
    // rerolled only after that system becomes invisible, so returning to it is
    // instantaneous and never reveals the previous image first. The accent
    // field deliberately reaches far beyond the product instead of collapsing
    // into a tight halo on an otherwise black wallpaper.
    Repeater {
        model: systemModel.count
        Item {
            x: 0
            y: 0
            width: 1920
            height: 1080
            opacity: root.showSystemBackdrop &&
                     systemRail.currentIndex === index ? 1.0 : 0

            RadialGradient {
                x: 360
                y: -340
                width: 1920
                height: 1480
                horizontalRadius: width * 0.50
                verticalRadius: height * 0.50
                gradient: Gradient {
                    GradientStop {
                        position: 0.0
                        color: Qt.rgba(systemModel.get(index).accent.r,
                                       systemModel.get(index).accent.g,
                                       systemModel.get(index).accent.b, 0.58)
                    }
                    GradientStop {
                        position: 0.28
                        color: Qt.rgba(systemModel.get(index).accent.r,
                                       systemModel.get(index).accent.g,
                                       systemModel.get(index).accent.b, 0.39)
                    }
                    GradientStop {
                        position: 0.58
                        color: Qt.rgba(systemModel.get(index).accent.r,
                                       systemModel.get(index).accent.g,
                                       systemModel.get(index).accent.b, 0.22)
                    }
                    GradientStop {
                        position: 0.82
                        color: Qt.rgba(systemModel.get(index).accent.r,
                                       systemModel.get(index).accent.g,
                                       systemModel.get(index).accent.b, 0.11)
                    }
                    GradientStop { position: 1.0; color: "transparent" }
                }
            }

            Image {
                id: systemHardwareImage
                x: 1010
                y: 88
                width: 850
                height: 350
                source: root.hardwarePhotoBySystem[index] || ""
                fillMode: Image.PreserveAspectFit
                asynchronous: true
                cache: true
                sourceSize.width: 838
                sourceSize.height: 338
            }
        }
    }

    // Two-buffer artwork swap. The old game remains visible until the new
    // image reports Ready; the two invisible loaders keep both D-pad
    // destinations decoded in advance. This also covers sort changes.
    Item {
        anchors.fill: parent
        visible: !root.showSystemBackdrop

        Image {
            id: upperArtworkA
            anchors.fill: parent
            fillMode: Image.PreserveAspectCrop
            // Neighbor art is decoded by the preloaders below. Make the
            // cache-hit promotion synchronous so title, row, and wallpaper
            // all change in the same selection event.
            asynchronous: false
            cache: true
            sourceSize.width: 1920
            sourceSize.height: 1080
            opacity: root.upperArtworkSlot === 0 ? 1.0 : 0.0
            onStatusChanged: if (status === Image.Ready) root.promoteUpperArtwork(0)
        }

        Image {
            id: upperArtworkB
            anchors.fill: parent
            fillMode: Image.PreserveAspectCrop
            asynchronous: false
            cache: true
            sourceSize.width: 1920
            sourceSize.height: 1080
            opacity: root.upperArtworkSlot === 1 ? 1.0 : 0.0
            onStatusChanged: if (status === Image.Ready) root.promoteUpperArtwork(1)
        }

        Image {
            id: upperArtworkPreloadPrevious
            x: -4
            y: -4
            width: 2
            height: 2
            asynchronous: true
            cache: true
            sourceSize.width: 1920
            sourceSize.height: 1080
            opacity: 0
        }

        Image {
            id: upperArtworkPreloadNext
            x: -4
            y: -4
            width: 2
            height: 2
            asynchronous: true
            cache: true
            sourceSize.width: 1920
            sourceSize.height: 1080
            opacity: 0
        }

        Image {
            id: upperArtworkPreloadEntry
            x: -4
            y: -4
            width: 2
            height: 2
            asynchronous: true
            cache: true
            sourceSize.width: 1920
            sourceSize.height: 1080
            opacity: 0
        }
    }

    Item {
        anchors.fill: parent
        visible: !root.showSystemBackdrop &&
                 (!root.activeGame || !root.artwork(root.activeGame))

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            y: 130
            text: systemModel.get(root.displaySystemIndex).mark
            color: root.accent
            opacity: 0.13
            font.family: global.fonts.condensed
            font.pixelSize: 430
            font.weight: Font.Bold
        }

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            y: 650
            width: 1380
            text: root.activeGame ? root.displayTitle(root.activeGame) : systemModel.get(root.displaySystemIndex).name
            color: "#dce2ee"
            opacity: 0.22
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            maximumLineCount: 2
            font.family: global.fonts.condensed
            font.pixelSize: 74
            font.weight: Font.DemiBold
        }
    }

    // Preserve the changing light and texture in the upper half of each
    // wallpaper while fully burying its original low-positioned hardware
    // beneath the navigation and card contrast field.
    Rectangle {
        x: 0
        y: 286
        width: parent.width
        height: parent.height - y
        visible: root.showSystemBackdrop
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#1207090d" }
            GradientStop { position: 0.18; color: "#b807090d" }
            GradientStop { position: 0.50; color: "#f207090d" }
            GradientStop { position: 1.0; color: "#ff07090d" }
        }
    }

    QtObject {
        id: previewA
        property string previewMode: ""
        property int systemIndex: -1
        property int gameIndex: -1
        property var previewGame: null
    }

    QtObject {
        id: previewB
        property string previewMode: ""
        property int systemIndex: -1
        property int gameIndex: -1
        property var previewGame: null
    }

    QtObject {
        id: previewC
        property string previewMode: ""
        property int systemIndex: -1
        property int gameIndex: -1
        property var previewGame: null
    }

    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#99070a10" }
            GradientStop { position: 0.42; color: "#35070a10" }
            GradientStop { position: 0.72; color: "#d9070a10" }
            GradientStop { position: 1.0; color: "#ff07090d" }
        }
    }

    Rectangle {
        anchors.fill: parent
        color: "#28000000"
    }

    // A quiet technical grid gives empty systems a deliberate visual state.
    Repeater {
        model: 12
        Rectangle {
            x: index * root.width / 11
            width: 1
            height: root.height
            color: "#12ffffff"
        }
    }
    Repeater {
        model: 7
        Rectangle {
            y: index * root.height / 6
            width: root.width
            height: 1
            color: "#0dffffff"
        }
    }

    // Fallback for conventional one-display Android handhelds. On the Thor
    // this entire PIP and its decoders remain disabled; the native player owns
    // the physical lower screen instead.
    Item {
        id: singleScreenPip
        z: 80
        // Single-screen list mode reserves the upper-right quadrant for the
        // preview. The list gives up two rows (nine -> seven), so the movie can
        // be substantially larger without covering a title, score, sort
        // control, or game row. Dual-screen Thor geometry is untouched because
        // this item is disabled there.
        property bool compactHomeList: root.page === "home" && root.homeViewMode === "list"
        property bool compactGameList: root.page === "games" && root.gameViewMode === "list"
        property bool swapHomeList: compactHomeList && !root.dualScreenDevice &&
                                    root.singleScreenMediaSwapped
        property bool swapGameList: compactGameList && !root.dualScreenDevice &&
                                    root.singleScreenMediaSwapped
        x: swapHomeList ? parent.width - width - 56 :
           (swapGameList ? listViewPanel.x :
            (compactHomeList ? parent.width - width - 226 : parent.width - width - 56))
        y: swapGameList ? listViewPanel.y - 76 + (600 - height) / 2 :
           (compactHomeList ? 112 : 102)
        width: compactHomeList ? 330 :
               (compactGameList ? (swapGameList ? 600 : 624) : 392)
        height: Math.round(width * 9 / 16)
        visible: root.previewPlacementMode !== "off" &&
                 !root.useBottomPreview() && root.singleCurrentSlot >= 0
        clip: true

        Rectangle {
            anchors.fill: parent
            color: "#e8070a0f"
            border.width: 2
            border.color: root.accent
            radius: 8
        }

        Image {
            anchors.fill: parent
            anchors.margins: 3
            source: root.artwork(root.activeGame)
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
            cache: true
        }

        Video {
            id: singleVideoA
            anchors.fill: parent
            anchors.margins: 3
            source: root.singleSourceA
            fillMode: VideoOutput.PreserveAspectCrop
            muted: !root.previewSoundEnabled || root.singleCurrentSlot !== 0
            loops: root.randomHomePreviewActive() ? 1 : MediaPlayer.Infinite
            autoPlay: source !== ""
            opacity: root.singleCurrentSlot === 0 ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 85 } }
            onPositionChanged: if (position > 0) root.promoteSingleVideo(0)
            onStopped: if (root.singleCurrentSlot === 0 && root.randomHomePreviewActive())
                           Qt.callLater(function() { root.advanceRandomHomePreview() })
        }

        Video {
            id: singleVideoB
            anchors.fill: parent
            anchors.margins: 3
            source: root.singleSourceB
            fillMode: VideoOutput.PreserveAspectCrop
            muted: !root.previewSoundEnabled || root.singleCurrentSlot !== 1
            loops: root.randomHomePreviewActive() ? 1 : MediaPlayer.Infinite
            autoPlay: source !== ""
            opacity: root.singleCurrentSlot === 1 ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 85 } }
            onPositionChanged: if (position > 0) root.promoteSingleVideo(1)
            onStopped: if (root.singleCurrentSlot === 1 && root.randomHomePreviewActive())
                           Qt.callLater(function() { root.advanceRandomHomePreview() })
        }

        Video {
            id: singleVideoC
            anchors.fill: parent
            anchors.margins: 3
            source: root.singleSourceC
            fillMode: VideoOutput.PreserveAspectCrop
            muted: !root.previewSoundEnabled || root.singleCurrentSlot !== 2
            loops: root.randomHomePreviewActive() ? 1 : MediaPlayer.Infinite
            autoPlay: source !== ""
            opacity: root.singleCurrentSlot === 2 ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 85 } }
            onPositionChanged: if (position > 0) root.promoteSingleVideo(2)
            onStopped: if (root.singleCurrentSlot === 2 && root.randomHomePreviewActive())
                           Qt.callLater(function() { root.advanceRandomHomePreview() })
        }
    }

    Item {
        id: chrome
        anchors.fill: parent

        Item {
            id: topBar
            x: 56
            y: 34
            width: parent.width - 112
            height: 58

            Item {
                x: 0
                anchors.verticalCenter: parent.verticalCenter
                width: 230
                height: 64
                property bool leftAnchoredMark:
                        root.brandSlugForSystem(root.displaySystemIndex) === "microsoft"
                visible: !root.showAvailableBrandRow &&
                         root.brandSlugForSystem(root.displaySystemIndex) !== "" &&
                         root.displaySystemIndex > 0

                Image {
                    id: activeBrandLogo
                    anchors.left: parent.left
                    anchors.verticalCenter: parent.verticalCenter
                    width: parent.leftAnchoredMark ? 64 : parent.width
                    height: parent.height
                    source: root.brandLogoForSystem(root.displaySystemIndex)
                    fillMode: Image.PreserveAspectFit
                    horizontalAlignment: Image.AlignLeft
                    asynchronous: false
                    cache: true
                    sourceSize.width: 760
                    sourceSize.height: 224
                }

                ColorOverlay {
                    anchors.fill: activeBrandLogo
                    source: activeBrandLogo
                    color: "white"
                    visible: root.useWhiteBrandLogo(root.displaySystemIndex)
                    cached: true
                }
            }

            Row {
                x: 0
                anchors.verticalCenter: parent.verticalCenter
                height: 58
                spacing: 22
                visible: root.showAvailableBrandRow

                Repeater {
                    model: root.availableBrandSlugs

                    Item {
                        width: modelData === "arcade" ? 122 :
                               (modelData === "sony" ? 130 :
                               (modelData === "microsoft" ? 58 : 160))
                        height: 58

                        Image {
                            anchors.fill: parent
                            anchors.margins: 0
                            source: modelData === "arcade" ?
                                    Qt.resolvedUrl("assets/logos-png/arcade.png") :
                                    Qt.resolvedUrl("assets/brands/" + modelData + ".png")
                            fillMode: Image.PreserveAspectFit
                            horizontalAlignment: Image.AlignLeft
                            asynchronous: false
                            cache: true
                            smooth: true
                            mipmap: true
                        }
                    }
                }
            }

            Rectangle {
                id: searchControl
                anchors.right: clockStatus.left
                anchors.rightMargin: 22
                anchors.verticalCenter: parent.verticalCenter
                width: root.searchOpen ? 520 : 46
                height: 46
                color: root.searchOpen ? "#e00a0e16" : "transparent"
                border.width: root.searchOpen ? 1 : 0
                border.color: root.searchOpen ? root.accent : "#42ffffff"
                radius: 23
                clip: true

                Behavior on width {
                    NumberAnimation { duration: 180; easing.type: Easing.OutCubic }
                }

                TextInput {
                    id: searchField
                    x: 20
                    anchors.verticalCenter: parent.verticalCenter
                    width: parent.width - (searchClear.visible ? 142 : 76)
                    visible: root.searchOpen
                    text: ""
                    color: "white"
                    selectionColor: root.accent
                    selectedTextColor: "#05070b"
                    font.family: global.fonts.sans
                    font.pixelSize: 21
                    clip: true
                    selectByMouse: true
                    inputMethodHints: Qt.ImhNoPredictiveText
                    function syncSearchQuery() {
                        var completeValue = String(text) + String(preeditText || "")
                        if (root.searchQuery === completeValue)
                            return
                        root.searchQuery = completeValue
                        searchChangeCommit.restart()
                    }
                    onTextChanged: syncSearchQuery()
                    onPreeditTextChanged: syncSearchQuery()
                    onAccepted: {
                        // Keep the editor focused until this Enter event has fully
                        // unwound. Otherwise Android can deliver the same event to
                        // the game list as A/Play after the keyboard disappears.
                        root.searchKeyboardAccepting = true
                        Qt.inputMethod.hide()
                        Qt.callLater(function() {
                            searchField.focus = false
                            root.forceActiveFocus()
                            root.searchKeyboardAccepting = false
                        })
                    }
                }

                Text {
                    x: 20
                    anchors.verticalCenter: parent.verticalCenter
                    visible: root.searchOpen && searchField.text.length === 0 &&
                             searchField.preeditText.length === 0
                    text: "SEARCH GAMES"
                    color: "#7f899b"
                    font.family: global.fonts.sans
                    font.pixelSize: 18
                    font.letterSpacing: 1.2
                }

                Item {
                    id: searchClear
                    anchors.right: searchMagnifier.left
                    anchors.rightMargin: 0
                    anchors.verticalCenter: parent.verticalCenter
                    // Keep the restrained 17px glyph, but give it a generous
                    // 58x46 touch target so it is easy to hit on a handheld.
                    width: 58
                    height: 46
                    visible: root.searchOpen && root.searchQuery !== ""

                    Rectangle {
                        anchors.centerIn: parent
                        width: 17
                        height: 2
                        radius: 1
                        rotation: 45
                        color: "#cbd2df"
                    }

                    Rectangle {
                        anchors.centerIn: parent
                        width: 17
                        height: 2
                        radius: 1
                        rotation: -45
                        color: "#cbd2df"
                    }

                    MouseArea {
                        x: -4
                        y: -2
                        width: parent.width + 8
                        height: parent.height + 4
                        onClicked: root.endSearch()
                    }
                }

                Item {
                    id: searchMagnifier
                    anchors.right: parent.right
                    anchors.rightMargin: 8
                    anchors.verticalCenter: parent.verticalCenter
                    width: 38
                    height: 38

                    Image {
                        anchors.centerIn: parent
                        width: 30
                        height: 30
                        source: Qt.resolvedUrl("assets/search-white.png")
                        fillMode: Image.PreserveAspectFit
                        smooth: true
                        mipmap: true
                        cache: true
                    }

                    MouseArea {
                        anchors.fill: parent
                        onClicked: {
                            if (!root.searchOpen)
                                root.beginSearch()
                            else {
                                searchField.forceActiveFocus()
                                Qt.inputMethod.show()
                            }
                        }
                    }
                }
            }

            Item {
                id: lucentSettingsButton
                anchors.right: searchControl.left
                // Keep the two icons visually and physically distinct. The old
                // expanded gear hit target nearly touched the search target.
                anchors.rightMargin: 30
                anchors.verticalCenter: parent.verticalCenter
                width: 38
                height: 38

                Image {
                    anchors.centerIn: parent
                    width: 30
                    height: 30
                    source: Qt.resolvedUrl("assets/settings-gear.svg")
                    fillMode: Image.PreserveAspectFit
                    smooth: true
                    mipmap: true
                    opacity: root.settingsOpen ? 0.68 : 1.0
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: {
                        root.endSearch()
                        root.settingsOpen = true
                        root.settingsIndex = 0
                        root.forceActiveFocus()
                    }
                }
            }

            Item {
                id: lucentBrowserButton
                anchors.right: lucentSettingsButton.left
                anchors.rightMargin: 24
                anchors.verticalCenter: parent.verticalCenter
                width: 42
                height: 42

                Image {
                    anchors.centerIn: parent
                    width: 31
                    height: 31
                    source: Qt.resolvedUrl("assets/globe-white.svg")
                    fillMode: Image.PreserveAspectFit
                    smooth: true
                    mipmap: true
                    opacity: 0.96
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: root.openLucentBrowser()
                }
            }

            Item {
                id: lucentRescanButton
                anchors.right: lucentBrowserButton.left
                anchors.rightMargin: 24
                anchors.verticalCenter: parent.verticalCenter
                width: 42
                height: 42

                Image {
                    id: lucentRescanIcon
                    anchors.centerIn: parent
                    width: 31
                    height: 31
                    source: Qt.resolvedUrl("assets/rescan-white.svg")
                    fillMode: Image.PreserveAspectFit
                    smooth: true
                    mipmap: true
                    opacity: root.importState !== "idle" &&
                             root.importState !== "complete" &&
                             root.importState !== "error" ? 0.62 : 0.96

                    RotationAnimation on rotation {
                        running: root.importState !== "idle" &&
                                 root.importState !== "complete" &&
                                 root.importState !== "error"
                        from: 0
                        to: 360
                        loops: Animation.Infinite
                        duration: 900
                    }
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: root.startMaintenanceRescan()
                }
            }

            Connections {
                target: Qt.inputMethod
                onVisibleChanged: {
                    // Closing Gboard by its chevron must hand the D-pad back
                    // to Pegasus even though Android keeps the edit session.
                    if (!root.searchKeyboardAccepting &&
                            !Qt.inputMethod.visible && root.searchOpen &&
                            searchField.activeFocus) {
                        searchField.focus = false
                        root.forceActiveFocus()
                    }
                }
            }

            Text {
                id: clockStatus
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                text: root.clockText + "     " + (isNaN(api.device.batteryPercent) ? "" : Math.round(api.device.batteryPercent * 100) + "%")
                color: "#aeb6c8"
                font.family: global.fonts.sans
                font.pixelSize: 20
                font.letterSpacing: 1
            }
        }

        Item {
            id: homePage
            anchors.fill: parent
            opacity: root.page === "home" ? 1 : 0
            scale: root.page === "home" ? 1 : 0.985
            visible: opacity > 0
            Behavior on opacity {
                NumberAnimation { duration: root.viewTransitionsEnabled ? 240 : 0;
                                  easing.type: Easing.OutCubic }
            }
            Behavior on scale {
                NumberAnimation { duration: root.viewTransitionsEnabled ? 260 : 0;
                                  easing.type: Easing.OutCubic }
            }
            transform: Translate {
                x: root.page === "home" ? 0 : -72
                Behavior on x {
                    NumberAnimation { duration: root.viewTransitionsEnabled ? 260 : 0;
                                      easing.type: Easing.OutCubic }
                }
            }

            Text {
                x: 58
                y: 150
                width: !root.dualScreenDevice && root.homeViewMode === "covers" ?
                       singleScreenPip.x - x - 28 : parent.width - 116
                visible: root.homeZone === 0
                text: systemModel.get(systemRail.currentIndex).name
                color: "white"
                font.family: global.fonts.condensed
                font.pixelSize: 66
                minimumPixelSize: 38
                fontSizeMode: Text.HorizontalFit
                font.weight: Font.Bold
                font.letterSpacing: 1
            }

            Text {
                x: 62
                y: 225
                visible: root.homeZone === 0
                text: systemRail.currentIndex === 0 ?
                      root.romGameCount() + " TITLES ACROSS EVERY SYSTEM" :
                      (root.selectedCollection ? root.selectedCollection.games.count + " TITLES" : "READY FOR YOUR LIBRARY")
                color: root.accent
                font.family: global.fonts.sans
                font.pixelSize: 18
                font.weight: Font.DemiBold
                font.letterSpacing: 3
            }

            Text {
                x: 62
                y: 267
                width: 780
                visible: root.homeZone === 0
                text: systemRail.currentIndex === 0 ?
                      "ONE LIBRARY  •  EVERY GAME  •  SCORES, RELEASES, AND DIRECT LAUNCH" :
                      (root.selectedCollection ? "SELECT TO BROWSE  •  GAMES LAUNCH DIRECTLY" :
                       "ADD GAMES TO  /GAMES/" + systemModel.get(systemRail.currentIndex).folder)
                color: "#aeb6c8"
                font.family: global.fonts.sans
                font.pixelSize: 18
                font.letterSpacing: 1
            }

            Text {
                x: 58
                y: 148
                width: root.homeViewMode === "list" ?
                       ((!root.dualScreenDevice ?
                         Math.min(singleScreenPip.x, homeListBoxArt.x) :
                         homeListBoxArt.x) - x - 28) :
                       ((!root.dualScreenDevice ? singleScreenPip.x : parent.width - 58) - x - 28)
                visible: root.homeZone > 0
                text: root.homeListBrowsingSystems ?
                      systemModel.get(systemRail.currentIndex).name :
                      (root.activeGame ? root.displayTitle(root.activeGame) : "CONTINUE PLAYING")
                color: "white"
                font.family: global.fonts.condensed
                font.pixelSize: 60
                minimumPixelSize: 36
                fontSizeMode: Text.HorizontalFit
                font.weight: Font.Bold
                maximumLineCount: 1
            }

            Text {
                x: 62
                y: 224
                visible: root.homeZone > 0
                text: root.homeListBrowsingSystems ?
                      root.homeSystemStatsText() : root.gameFactsText(root.activeGame)
                color: root.accent
                font.family: global.fonts.sans
                font.pixelSize: 22
                font.weight: Font.Bold
                font.letterSpacing: 1.1
            }

            Text {
                x: 62
                y: 262
                visible: root.homeZone > 0
                text: root.homeViewMode === "list" ?
                      root.homeSystemInstructionText() :
                      root.homeShelfName(root.homeZone) + "     A  PLAY"
                color: "#aeb6c8"
                font.family: global.fonts.sans
                font.pixelSize: 17
                font.letterSpacing: 2
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                y: 964
                visible: root.homeViewMode === "covers" &&
                         !root.settingsOpen && root.coverVerticalNavigationText() !== ""
                text: root.coverVerticalNavigationText()
                color: root.accent
                font.family: global.fonts.sans
                font.pixelSize: 14
                font.weight: Font.DemiBold
                font.letterSpacing: 1.8
            }

            ListView {
                id: systemRail
                visible: root.coverZoneVisible(0)
                x: 48
                y: root.coverContentTop +
                   (root.coverZonePosition(0) - root.coverWindowStart()) *
                   root.coverRowSlotHeight +
                   (root.coverViewRowCount === 1 ? 112 :
                    (root.coverViewRowCount === 2 ? 50 : 34))
                width: parent.width - 96
                height: root.coverRowSlotHeight - 38
                orientation: ListView.Horizontal
                model: systemModel
                spacing: 14
                // The selected delegate grows beyond its nominal height. The
                // rail has ample vertical breathing room, so do not cut its
                // top rim at the ListView boundary.
                clip: false
                // No half-screen end spacers: the first/last cards clamp to
                // the 48 px safe edge while middle selections remain centered.
                focus: root.homeZone === 0
                // Keep the selected platform centered, but visibly translate
                // the entire rail in the direction of travel. The short,
                // retargetable duration preserves rapid D-pad response while
                // making every one-step system change spatially legible.
                highlightMoveDuration: 165
                highlightRangeMode: ListView.ApplyRange
                // The rail spans the full display. The preferred position is
                // the selected delegate's leading edge, so offset by half its
                // width to center it on the physical screen precisely.
                preferredHighlightBegin: (width -
                        (root.coverViewRowCount === 1 ? 360 :
                         (root.coverViewRowCount === 2 ? 250 : 210))) / 2
                preferredHighlightEnd: preferredHighlightBegin
                keyNavigationWraps: true
                keyNavigationEnabled: false
                onCurrentIndexChanged: {
                    if (root.previewReady) {
                        if (root.page === "home" && root.homeViewMode === "list") {
                            root.chooseSystemWallpaper(systemRail.currentIndex)
                            homeListRebuild.restart()
                        } else if (root.page === "home" && root.homeZone === 0) {
                            // Keep the D-pad event turn identical to gameRail:
                            // update selection now, coalesce expensive visual
                            // and preview work immediately afterward.
                            // The destination layer is already decoded. Swap
                            // immediately, then reroll only the departed layer.
                            root.chooseSystemWallpaper(systemRail.currentIndex)
                            systemChangeCommit.restart()
                        }
                    }
                }

                delegate: Item {
                    id: systemCard
                    property bool isSelected: ListView.isCurrentItem && root.homeZone === 0
                    width: root.coverViewRowCount === 1 ? 360 :
                           (root.coverViewRowCount === 2 ? 250 : 210)
                    height: root.coverViewRowCount === 1 ? 250 :
                            (root.coverViewRowCount === 2 ? 174 : 146)
                    scale: isSelected ? 1.10 : 0.88
                    opacity: isSelected ? 1.0 : 0.46
                    Behavior on scale { NumberAnimation { duration: 145; easing.type: Easing.OutCubic } }
                    Behavior on opacity { NumberAnimation { duration: 125 } }

                    Rectangle {
                        anchors.fill: parent
                        color: systemCard.isSelected ?
                               Qt.darker(model.accent, 4.5) : "#9b11141b"
                        border.width: systemCard.isSelected ? 5 : 1
                        border.color: systemCard.isSelected ? model.accent : "#42ffffff"
                        radius: 7
                    }

                    Rectangle {
                        x: 18
                        y: 18
                        width: root.coverViewRowCount === 1 ? 64 : 46
                        height: systemCard.isSelected ? 7 : 4
                        color: model.accent
                    }

                    Image {
                        id: systemLogo
                        x: 12
                        // Vertically center the wordmark in the clear space
                        // between the accent stroke and the year label.
                        y: 28
                        width: parent.width - 24
                        height: parent.height - 72
                        source: model.folder === "all" ? "" :
                                Qt.resolvedUrl("assets/logos-png/" + model.folder + ".png")
                        visible: model.folder !== "all" && status !== Image.Error
                        fillMode: Image.PreserveAspectFit
                        horizontalAlignment: model.folder === "windows" ?
                                Image.AlignLeft : Image.AlignHCenter
                        asynchronous: false
                        cache: true
                        smooth: true
                        mipmap: true
                        sourceSize.width: 840
                        sourceSize.height: 360
                    }

                    Text {
                        x: 12
                        y: 30
                        width: parent.width - 24
                        height: parent.height - 74
                        visible: model.folder === "all" || systemLogo.status === Image.Error
                        text: model.folder === "all" ? "ALL\nSYSTEMS" : model.mark
                        color: "#f4f7fc"
                        horizontalAlignment: Text.AlignHCenter
                        verticalAlignment: Text.AlignVCenter
                        lineHeight: 0.82
                        font.family: global.fonts.condensed
                        font.pixelSize: root.coverViewRowCount === 1 ? 52 : 38
                        font.weight: Font.Black
                        font.letterSpacing: 2
                    }

                    Text {
                        x: 18
                        y: parent.height - 28
                        width: parent.width - 34
                        text: model.years
                        color: model.accent
                        font.family: global.fonts.sans
                        font.pixelSize: root.coverViewRowCount === 1 ? 14 : 12
                        font.weight: Font.DemiBold
                        font.letterSpacing: 2
                    }

                    MouseArea {
                        anchors.fill: parent
                        onClicked: {
                            systemRail.currentIndex = index
                            root.homeZone = 0
                            root.enterCollection()
                        }
                    }
                }
            }

            Rectangle {
                visible: false
                x: 58
                y: 580
                width: parent.width - 116
                height: 1
                color: "#35ffffff"
            }

            Item {
                id: shelfViewport
                opacity: root.homeViewMode === "covers" ? 1 : 0
                visible: opacity > 0
                x: 0
                y: root.coverContentTop
                width: parent.width
                height: root.coverContentHeight
                clip: true
                Behavior on opacity {
                    NumberAnimation { duration: root.viewTransitionsEnabled ? 220 : 0 }
                }

                Item {
                    id: shelfStack
                    width: parent.width
                    height: root.coverRowSlotHeight * 8
                    y: -root.coverWindowStart() * root.coverRowSlotHeight
                    Behavior on y { NumberAnimation { duration: 210; easing.type: Easing.OutCubic } }

                    Item {
                        y: root.coverZonePosition(1) * root.coverRowSlotHeight
                        width: parent.width
                        height: root.coverRowSlotHeight

                        Text {
                            x: 58
                            y: 10
                            text: "CONTINUE PLAYING"
                            color: root.homeZone === 1 ? "white" : "#8f98aa"
                            font.family: global.fonts.sans
                            font.pixelSize: 18
                            font.weight: Font.DemiBold
                            font.letterSpacing: 3
                        }

                        ListView {
                            id: recentRail
                            property int zone: 1
                            x: 48
                            y: 44
                            width: parent.width - 96
                            height: parent.parent.height - 46
                            orientation: ListView.Horizontal
                            model: recentModel
                            delegate: homeShelfCard
                            spacing: 18
                            clip: false
                            focus: root.homeZone === 1
                            highlightMoveDuration: 180
                            highlightRangeMode: ListView.ApplyRange
                            preferredHighlightBegin: (width - root.coverShelfCardWidth) / 2
                            preferredHighlightEnd: preferredHighlightBegin
                            keyNavigationWraps: true
                            keyNavigationEnabled: false
                            onCurrentIndexChanged: if (root.previewReady && root.page === "home" && root.homeZone === 1) root.activateShelfPreview(1)
                        }
                    }

                    Item {
                        y: root.coverZonePosition(2) * root.coverRowSlotHeight
                        width: parent.width
                        height: root.coverRowSlotHeight

                        Text {
                            x: 58
                            y: 10
                            text: "MOST PLAYED"
                            color: root.homeZone === 2 ? "white" : "#8f98aa"
                            font.family: global.fonts.sans
                            font.pixelSize: 18
                            font.weight: Font.DemiBold
                            font.letterSpacing: 3
                        }

                        ListView {
                            id: mostPlayedRail
                            property int zone: 2
                            x: 48
                            y: 44
                            width: parent.width - 96
                            height: parent.parent.height - 46
                            orientation: ListView.Horizontal
                            model: mostPlayedModel
                            delegate: homeShelfCard
                            spacing: 18
                            clip: false
                            focus: root.homeZone === 2
                            highlightMoveDuration: 180
                            highlightRangeMode: ListView.ApplyRange
                            preferredHighlightBegin: (width - root.coverShelfCardWidth) / 2
                            preferredHighlightEnd: preferredHighlightBegin
                            keyNavigationWraps: true
                            keyNavigationEnabled: false
                            onCurrentIndexChanged: if (root.previewReady && root.page === "home" && root.homeZone === 2) root.activateShelfPreview(2)
                        }
                    }

                    Item {
                        y: root.coverZonePosition(3) * root.coverRowSlotHeight
                        width: parent.width
                        height: root.coverRowSlotHeight

                        Text {
                            x: 58
                            y: 10
                            text: "RECENTLY ADDED"
                            color: root.homeZone === 3 ? "white" : "#8f98aa"
                            font.family: global.fonts.sans
                            font.pixelSize: 18
                            font.weight: Font.DemiBold
                            font.letterSpacing: 3
                        }

                        ListView {
                            id: recentlyAddedRail
                            property int zone: 3
                            x: 48
                            y: 44
                            width: parent.width - 96
                            height: parent.parent.height - 46
                            orientation: ListView.Horizontal
                            model: recentlyAddedModel
                            delegate: homeShelfCard
                            spacing: 18
                            clip: false
                            focus: root.homeZone === 3
                            highlightMoveDuration: 180
                            highlightRangeMode: ListView.ApplyRange
                            preferredHighlightBegin: (width - root.coverShelfCardWidth) / 2
                            preferredHighlightEnd: preferredHighlightBegin
                            keyNavigationWraps: true
                            keyNavigationEnabled: false
                            onCurrentIndexChanged: if (root.previewReady && root.page === "home" && root.homeZone === 3) root.activateShelfPreview(3)
                        }
                    }

                    Item {
                        y: root.coverZonePosition(4) * root.coverRowSlotHeight
                        width: parent.width
                        height: root.coverRowSlotHeight

                        Text {
                            x: 58
                            y: 10
                            text: "CRITIC SCORE"
                            color: root.homeZone === 4 ? "white" : "#8f98aa"
                            font.family: global.fonts.sans
                            font.pixelSize: 18
                            font.weight: Font.DemiBold
                            font.letterSpacing: 3
                        }

                        ListView {
                            id: criticRail
                            property int zone: 4
                            x: 48
                            y: 44
                            width: parent.width - 96
                            height: parent.parent.height - 46
                            orientation: ListView.Horizontal
                            model: allCriticSortModel
                            delegate: homeShelfCard
                            spacing: 18
                            clip: false
                            focus: root.homeZone === 4
                            highlightMoveDuration: 180
                            highlightRangeMode: ListView.ApplyRange
                            preferredHighlightBegin: (width - root.coverShelfCardWidth) / 2
                            preferredHighlightEnd: preferredHighlightBegin
                            keyNavigationWraps: true
                            keyNavigationEnabled: false
                            onCurrentIndexChanged: if (root.previewReady && root.page === "home" && root.homeZone === 4) root.activateShelfPreview(4)
                        }
                    }

                    Item {
                        y: root.coverZonePosition(5) * root.coverRowSlotHeight
                        width: parent.width
                        height: root.coverRowSlotHeight

                        Text {
                            x: 58; y: 10
                            text: "USER SCORE"
                            color: root.homeZone === 5 ? "white" : "#8f98aa"
                            font.family: global.fonts.sans
                            font.pixelSize: 18
                            font.weight: Font.DemiBold
                            font.letterSpacing: 3
                        }

                        ListView {
                            id: userRail
                            property int zone: 5
                            x: 48; y: 44
                            width: parent.width - 96; height: parent.parent.height - 46
                            orientation: ListView.Horizontal
                            model: allUserSortModel
                            delegate: homeShelfCard
                            spacing: 18; clip: false
                            focus: root.homeZone === 5
                            highlightMoveDuration: 180
                            highlightRangeMode: ListView.ApplyRange
                            preferredHighlightBegin: (width - root.coverShelfCardWidth) / 2
                            preferredHighlightEnd: preferredHighlightBegin
                            keyNavigationWraps: true
                            keyNavigationEnabled: false
                            onCurrentIndexChanged: if (root.previewReady && root.page === "home" && root.homeZone === 5) root.activateShelfPreview(5)
                        }
                    }

                    Item {
                        y: root.coverZonePosition(6) * root.coverRowSlotHeight
                        width: parent.width
                        height: root.coverRowSlotHeight

                        Text {
                            x: 58; y: 10
                            text: "A–Z"
                            color: root.homeZone === 6 ? "white" : "#8f98aa"
                            font.family: global.fonts.sans
                            font.pixelSize: 18
                            font.weight: Font.DemiBold
                            font.letterSpacing: 3
                        }

                        ListView {
                            id: alphaRail
                            property int zone: 6
                            x: 48; y: 44
                            width: parent.width - 96; height: parent.parent.height - 46
                            orientation: ListView.Horizontal
                            model: allAlphaSortModel
                            delegate: homeShelfCard
                            spacing: 18; clip: false
                            focus: root.homeZone === 6
                            highlightMoveDuration: 180
                            highlightRangeMode: ListView.ApplyRange
                            preferredHighlightBegin: (width - root.coverShelfCardWidth) / 2
                            preferredHighlightEnd: preferredHighlightBegin
                            keyNavigationWraps: true
                            keyNavigationEnabled: false
                            onCurrentIndexChanged: if (root.previewReady && root.page === "home" && root.homeZone === 6) root.activateShelfPreview(6)
                        }
                    }

                    Item {
                        y: root.coverZonePosition(7) * root.coverRowSlotHeight
                        width: parent.width
                        height: root.coverRowSlotHeight

                        Text {
                            x: 58; y: 10
                            text: "RELEASE DATE"
                            color: root.homeZone === 7 ? "white" : "#8f98aa"
                            font.family: global.fonts.sans
                            font.pixelSize: 18
                            font.weight: Font.DemiBold
                            font.letterSpacing: 3
                        }

                        ListView {
                            id: releaseRail
                            property int zone: 7
                            x: 48; y: 44
                            width: parent.width - 96; height: parent.parent.height - 46
                            orientation: ListView.Horizontal
                            model: allReleaseSortModel
                            delegate: homeShelfCard
                            spacing: 18; clip: false
                            focus: root.homeZone === 7
                            highlightMoveDuration: 180
                            highlightRangeMode: ListView.ApplyRange
                            preferredHighlightBegin: (width - root.coverShelfCardWidth) / 2
                            preferredHighlightEnd: preferredHighlightBegin
                            keyNavigationWraps: true
                            keyNavigationEnabled: false
                            onCurrentIndexChanged: if (root.previewReady && root.page === "home" && root.homeZone === 7) root.activateShelfPreview(7)
                        }
                    }
                }
            }

            Item {
                id: homeListBoxArt
                visible: root.homeViewMode === "list" &&
                         root.homeListFocusColumn === 1 && root.activeGame
                x: !root.dualScreenDevice && root.singleScreenMediaSwapped ?
                   parent.width - 330 - 226 : parent.width - width - 58
                // Pin the visible artwork column to the category controls.
                // Using their actual geometry avoids drift if either header
                // layout changes and works for both Thor and single-screen UI.
                y: homeListPanel.y + homeCategoryTabs.y +
                   homeCategoryTabs.height - height
                // On dual-screen hardware the tab strip now ends beside this
                // column, so artwork can occupy the complete header height
                // instead of being squeezed above Release. Keep conventional
                // one-screen geometry compact so it never collides with PIP.
                width: root.dualScreenDevice ? 246 : 150
                height: root.dualScreenDevice ? 274 : 198
                z: 82

                Image {
                    id: homeListBoxArtA
                    anchors.fill: parent
                    source: root.boxArtworkSourceA
                    fillMode: Image.PreserveAspectFit
                    horizontalAlignment: Image.AlignRight
                    verticalAlignment: Image.AlignBottom
                    asynchronous: false
                    cache: true
                    smooth: true
                    mipmap: true
                    opacity: root.boxArtworkSlot === 0 ? 1 : 0
                    onStatusChanged: if (status === Image.Ready) root.promoteBoxArtwork(0)
                }

                Image {
                    id: homeListBoxArtB
                    anchors.fill: parent
                    source: root.boxArtworkSourceB
                    fillMode: Image.PreserveAspectFit
                    horizontalAlignment: Image.AlignRight
                    verticalAlignment: Image.AlignBottom
                    asynchronous: false
                    cache: true
                    smooth: true
                    mipmap: true
                    opacity: root.boxArtworkSlot === 1 ? 1 : 0
                    onStatusChanged: if (status === Image.Ready) root.promoteBoxArtwork(1)
                }

                Image {
                    id: boxArtworkPreloadPrevious
                    x: -4; y: -4; width: 2; height: 2; opacity: 0
                    asynchronous: true
                    cache: true
                    sourceSize.width: 640
                    sourceSize.height: 900
                }

                Image {
                    id: boxArtworkPreloadNext
                    x: -4; y: -4; width: 2; height: 2; opacity: 0
                    asynchronous: true
                    cache: true
                    sourceSize.width: 640
                    sourceSize.height: 900
                }

                Image {
                    id: boxArtworkPreloadEntry
                    x: -4; y: -4; width: 2; height: 2; opacity: 0
                    asynchronous: true
                    cache: true
                    sourceSize.width: 640
                    sourceSize.height: 900
                }
            }

            Item {
                id: homeListPanel
                x: 48
                y: 324
                width: parent.width - 96
                height: 708
                opacity: root.homeViewMode === "list" ? 1 : 0
                visible: opacity > 0
                scale: root.homeViewMode === "list" ? 1 : 0.985
                Behavior on opacity {
                    NumberAnimation { duration: root.viewTransitionsEnabled ? 230 : 0 }
                }
                Behavior on scale {
                    NumberAnimation { duration: root.viewTransitionsEnabled ? 240 : 0;
                                      easing.type: Easing.OutCubic }
                }

                Rectangle {
                    x: 0
                    y: 0
                    width: 560
                    height: parent.height
                    color: "transparent"
                    border.width: 0

                    ListView {
                        id: homeSystemList
                        x: 10
                        y: 0
                        width: parent.width - 20
                        // Exactly nine 70 px rows plus eight 4 px gaps. The
                        // viewport can never expose a fractional row.
                        height: 662
                        model: systemModel
                        currentIndex: systemRail.currentIndex
                        spacing: 4
                        clip: true
                        cacheBuffer: height * 2
                        snapMode: ListView.SnapToItem
                        boundsBehavior: Flickable.StopAtBounds
                        keyNavigationEnabled: false
                        highlightMoveDuration: 110
                        highlightRangeMode: ListView.ApplyRange
                        preferredHighlightBegin: (height - 70) / 2
                        preferredHighlightEnd: (height - 70) / 2

                        delegate: Rectangle {
                            id: homeSystemRow
                            property bool isSelected: ListView.isCurrentItem
                            width: homeSystemList.width
                            height: 70
                            color: isSelected ? model.accent :
                                   (index % 2 === 0 ? "#66060a10" : "#76060a10")
                            border.width: 0
                            radius: 6

                            Image {
                                id: homeSystemLogo
                                x: 14
                                anchors.verticalCenter: parent.verticalCenter
                                width: 112
                                height: 44
                                source: model.folder === "all" ?
                                        Qt.resolvedUrl("assets/hardware-cutouts/all/0.png") :
                                        Qt.resolvedUrl("assets/logos-png/" + model.folder + ".png")
                                visible: status !== Image.Error
                                fillMode: Image.PreserveAspectFit
                                horizontalAlignment: Image.AlignLeft
                                asynchronous: false
                                cache: true
                                smooth: true
                                mipmap: true
                            }

                            Text {
                                x: 14
                                anchors.verticalCenter: parent.verticalCenter
                                width: 112
                                visible: homeSystemLogo.status === Image.Error
                                text: model.mark
                                color: homeSystemRow.isSelected ? "#071016" : "white"
                                horizontalAlignment: Text.AlignHCenter
                                font.family: global.fonts.condensed
                                font.pixelSize: model.folder === "all" ? 17 : 25
                                font.weight: Font.Black
                            }

                            Text {
                                x: 142
                                y: 12
                                width: parent.width - 232
                                text: model.name
                                color: homeSystemRow.isSelected ? "#071016" : "#eef1f6"
                                fontSizeMode: Text.HorizontalFit
                                minimumPixelSize: 14
                                font.family: global.fonts.sans
                                font.pixelSize: 18
                                font.weight: Font.Bold
                            }

                            Text {
                                x: 142
                                y: 40
                                text: model.years
                                color: homeSystemRow.isSelected ? "#18251f" : model.accent
                                font.family: global.fonts.sans
                                font.pixelSize: 12
                                font.weight: Font.Bold
                                font.letterSpacing: 1.2
                            }

                            Text {
                                anchors.right: parent.right
                                anchors.rightMargin: 14
                                anchors.verticalCenter: parent.verticalCenter
                                text: root.systemGameCount(index)
                                color: homeSystemRow.isSelected ? "#071016" : "#aeb6c8"
                                font.family: global.fonts.condensed
                                font.pixelSize: 21
                                font.weight: Font.Bold
                            }

                            MouseArea {
                                anchors.fill: parent
                                onClicked: {
                                    systemRail.currentIndex = index
                                    root.homeListFocusColumn = 0
                                    root.rebuildHomeList()
                                }
                            }
                        }
                    }
                }

                Item {
                    // Twelve pixels of separation from the system column is
                    // sufficient and gives the seven-category strip more room.
                    x: 572
                    y: 0
                    width: parent.width - x
                    height: parent.height

                    Row {
                        id: homeCategoryTabs
                        x: 0
                        y: 0
                        // Keep every tab's geometry fixed while focus or the
                        // selected system changes. All Systems intentionally
                        // leaves the reserved box-art column empty instead of
                        // making seven controls jump wider for one state.
                        width: root.dualScreenDevice ?
                               Math.max(700, homeListBoxArt.x - homeListPanel.x -
                                        parent.x - 24) : parent.width
                        height: 58
                        spacing: 6

                        Repeater {
                            model: ["CONTINUE", "MOST PLAYED", "RECENTLY ADDED",
                                    "CRITIC", "USER", "A–Z", "RELEASE"]
                            Rectangle {
                                property int category: index + 1
                                property bool isSelected: root.homeListCategory === category
                                width: (homeCategoryTabs.width - 36) / 7
                                height: 58
                                color: isSelected ?
                                       Qt.rgba(root.accent.r, root.accent.g,
                                               root.accent.b, 0.90) : "#76060a10"
                                border.width: isSelected ? 2 : 1
                                border.color: isSelected ?
                                              Qt.lighter(root.accent, 1.15) :
                                              "#30ffffff"
                                radius: 7

                                Text {
                                    anchors.centerIn: parent
                                    width: parent.width - 14
                                    text: modelData
                                    color: parent.isSelected ? "#071016" : "#e3e7ef"
                                    horizontalAlignment: Text.AlignHCenter
                                    fontSizeMode: Text.HorizontalFit
                                    minimumPixelSize: 10
                                    font.family: global.fonts.sans
                                    font.pixelSize: 13
                                    font.weight: Font.Bold
                                    font.letterSpacing: 0.45
                                }

                                MouseArea {
                                    anchors.fill: parent
                                    onClicked: {
                                        root.homeListCategory = parent.category
                                        root.homeListFocusColumn = 1
                                        root.rebuildHomeList()
                                    }
                                }
                            }
                        }
                    }

                    ListView {
                        id: homeListRail
                        x: 0
                        y: 76
                        width: parent.width
                        // Seven 76 px rows plus six 4 px gaps keeps the chosen
                        // row centered without clipping the first or last row.
                        height: 556
                        model: root.homeListEntries
                        spacing: 4
                        clip: true
                        cacheBuffer: height * 2
                        snapMode: ListView.SnapToItem
                        boundsBehavior: Flickable.StopAtBounds
                        keyNavigationEnabled: false
                        highlightMoveDuration: 100
                        highlightRangeMode: ListView.ApplyRange
                        preferredHighlightBegin: (height - 76) / 2
                        preferredHighlightEnd: (height - 76) / 2
                        onCurrentIndexChanged: {
                            if (root.previewReady && root.page === "home" &&
                                    root.homeViewMode === "list")
                                root.activateHomeListPreview()
                        }

                        delegate: Rectangle {
                            id: homeGameRow
                            property bool isSelected: ListView.isCurrentItem &&
                                    root.homeListFocusColumn === 1
                            property var game: root.homeListGameAt(index)
                            width: homeListRail.width
                            height: 76
                            color: isSelected ? root.homeListAccentForGame(game) :
                                   (index % 2 === 0 ? "#78060a10" : "#86060a10")
                            border.width: 0
                            radius: 7

                            Text {
                                x: 20
                                anchors.verticalCenter: parent.verticalCenter
                                width: 54
                                text: (index < 9 ? "0" : "") + (index + 1)
                                color: homeGameRow.isSelected ? "#03050a" : "#647087"
                                font.family: global.fonts.condensed
                                font.pixelSize: 19
                                font.weight: Font.Bold
                            }

                            Text {
                                x: 78
                                anchors.verticalCenter: parent.verticalCenter
                                width: parent.width - 580
                                text: root.displayTitle(game)
                                color: homeGameRow.isSelected ? "#03050a" : "#eef1f6"
                                elide: Text.ElideRight
                                font.family: global.fonts.sans
                                font.pixelSize: 23
                                font.weight: homeGameRow.isSelected ? Font.Bold : Font.DemiBold
                            }

                            Text {
                                anchors.right: parent.right
                                anchors.rightMargin: 20
                                anchors.verticalCenter: parent.verticalCenter
                                width: 480
                                text: root.gameFactsText(game)
                                color: homeGameRow.isSelected ? "#03050a" :
                                       root.homeListAccentForGame(game)
                                horizontalAlignment: Text.AlignRight
                                fontSizeMode: Text.HorizontalFit
                                minimumPixelSize: 14
                                font.family: global.fonts.sans
                                font.pixelSize: 18
                                font.weight: Font.Bold
                            }

                            MouseArea {
                                anchors.fill: parent
                                pressAndHoldInterval: 800
                                onPressAndHold: root.openGameActions(game, index)
                                onClicked: {
                                    if (root.gameActionOpen) return
                                    homeListRail.currentIndex = index
                                    root.homeListFocusColumn = 1
                                    root.launch(game)
                                }
                            }
                        }
                    }

                    Text {
                        anchors.centerIn: homeListRail
                        visible: root.homeListEntries.length === 0
                        text: "NO " + root.homeShelfName(root.homeListCategory) +
                              " GAMES FOR " + systemModel.get(systemRail.currentIndex).name
                        color: "#aeb6c8"
                        font.family: global.fonts.sans
                        font.pixelSize: 22
                        font.weight: Font.DemiBold
                        font.letterSpacing: 1.2
                    }
                }
            }
        }

        Item {
            id: gamesPage
            anchors.fill: parent
            opacity: root.page === "games" ? 1 : 0
            scale: root.page === "games" ? 1 : 0.985
            visible: opacity > 0
            Behavior on opacity {
                NumberAnimation { duration: root.viewTransitionsEnabled ? 240 : 0;
                                  easing.type: Easing.OutCubic }
            }
            Behavior on scale {
                NumberAnimation { duration: root.viewTransitionsEnabled ? 260 : 0;
                                  easing.type: Easing.OutCubic }
            }
            transform: Translate {
                x: root.page === "games" ? 0 : 72
                Behavior on x {
                    NumberAnimation { duration: root.viewTransitionsEnabled ? 260 : 0;
                                      easing.type: Easing.OutCubic }
                }
            }

            Text {
                id: allSystemsLibraryLabel
                x: 58
                y: 130
                visible: root.allSystemsActive
                text: "ALL SYSTEMS"
                color: "white"
                font.family: global.fonts.condensed
                font.pixelSize: 31
                font.weight: Font.Bold
                font.letterSpacing: 2.6
            }

            Rectangle {
                x: 282
                y: 135
                width: 2
                height: 27
                visible: root.allSystemsActive
                color: root.accent
            }

            Text {
                x: root.allSystemsActive ? 304 : 58
                y: root.allSystemsActive ? 139 : 142
                text: systemModel.get(root.displaySystemIndex).name
                color: root.accent
                font.family: global.fonts.sans
                font.pixelSize: root.allSystemsActive ? 20 : 22
                font.weight: Font.DemiBold
                font.letterSpacing: 3
            }

            Text {
                x: 58
                y: 180
                // On a one-screen handheld, reserve the top-right preview's
                // footprint so long game titles and facts never render under
                // the PIP, including the larger single-screen list preview.
                width: !root.dualScreenDevice ?
                       ((root.singleScreenMediaSwapped && root.gameViewMode === "list" ?
                         parent.width - 624 - 56 : singleScreenPip.x) - x - 28) :
                       parent.width - 116
                height: 68
                text: root.activeGame ? root.displayTitle(root.activeGame) :
                      (root.searchQuery !== "" ? "NO MATCHING GAMES" : "NO GAMES YET")
                color: "white"
                font.family: global.fonts.condensed
                font.pixelSize: 58
                minimumPixelSize: 34
                fontSizeMode: Text.HorizontalFit
                font.weight: Font.Bold
                maximumLineCount: 1
            }

            Text {
                x: 62
                y: 253
                text: root.gameFactsText(root.activeGame)
                color: root.accent
                font.family: global.fonts.sans
                font.pixelSize: 22
                font.weight: Font.Bold
                font.letterSpacing: 1.1
            }

            Text {
                x: 62
                y: 286
                text: (root.allSystemsActive || root.activeCollection) ?
                      (activeGameCount > 0 ? gameRail.currentIndex + 1 : 0) +
                      " / " + activeGameCount +
                      "     SELECT TO PLAY" :
                      "ADD GAMES TO  /GAMES/" + systemModel.get(root.activeSystemIndex).folder
                color: "#aeb6c8"
                font.family: global.fonts.sans
                font.pixelSize: 17
                font.letterSpacing: 2
            }

            Rectangle {
                x: 58
                y: 326
                width: 460
                height: 2
                color: root.accent
            }

            Row {
                x: 48
                y: 350
                spacing: 8

                Repeater {
                    model: ["critic", "user", "alpha", "release"]
                    Rectangle {
                        width: 142
                        height: 44
                        color: "transparent"
                        clip: true
                        border.width: 1
                        border.color: root.sortMode === modelData ?
                                      Qt.lighter(root.accent, 1.18) : "#38ffffff"
                        radius: 7

                        ShaderEffectSource {
                            id: sortGlassSource
                            anchors.fill: parent
                            sourceItem: root.upperArtworkSlot === 0 ? upperArtworkA : upperArtworkB
                            sourceRect: Qt.rect(48 + index * 150 - 16, 350 - 16,
                                                142 + 32, 44 + 32)
                            textureSize: Qt.size(142 + 32, 44 + 32)
                            live: true
                            smooth: true
                            visible: false
                        }

                        Rectangle {
                            anchors.fill: parent
                            visible: !root.liquidGlassEnabled
                            color: root.sortMode === modelData ?
                                   Qt.rgba(root.accent.r, root.accent.g,
                                           root.accent.b, 0.90) : "#76060a10"
                            radius: 7
                        }

                        ShaderEffect {
                            anchors.fill: parent
                            visible: root.liquidGlassEnabled
                            property variant source: sortGlassSource
                            property size glassSize: Qt.size(width, height)
                            property real cornerRadius: 7
                            property real edgeThickness: 14
                            property real distortionStrength: 11.0
                            property real scatterRadius: 2.8
                            property real samplePadding: 16
                            property color glassTint: root.sortMode === modelData ?
                                Qt.rgba(root.accent.r, root.accent.g, root.accent.b, 0.80) :
                                Qt.rgba(0.025, 0.04, 0.07, 0.38)
                            fragmentShader: root.liquidGlassFragmentShader
                        }

                        Rectangle {
                            anchors.fill: parent
                            anchors.margins: 2
                            color: "transparent"
                            border.width: 1
                            border.color: root.sortMode === modelData ?
                                          "#42ffffff" : "#20ffffff"
                            radius: 5
                        }

                        Text {
                            anchors.centerIn: parent
                            text: root.sortLabel(modelData)
                            color: root.sortMode === modelData ? "#071016" : "#e3e7ef"
                            font.family: global.fonts.sans
                            font.pixelSize: 15
                            font.weight: Font.Bold
                            font.letterSpacing: 0.55
                        }

                        MouseArea {
                            anchors.fill: parent
                            onClicked: {
                                root.sortMode = modelData
                                root.scheduleNavigationPersistence()
                                gameRail.currentIndex = 0
                                sortChangeCommit.restart()
                            }
                        }
                    }
                }
            }

            ListView {
                id: gameRail
                x: 48
                y: 450
                width: parent.width - 96
                // Reserve a real footer safe area. Cards may scale within this
                // rail, but can never render behind the view label or controls.
                height: 500
                orientation: ListView.Horizontal
                model: activeGameModel
                spacing: 22
                clip: false
                focus: root.page === "games"
                highlightMoveDuration: 230
                highlightRangeMode: ListView.ApplyRange
                preferredHighlightBegin: (width - 250) / 2
                preferredHighlightEnd: (width - 250) / 2
                keyNavigationWraps: true
                keyNavigationEnabled: false
                opacity: root.gameViewMode === "covers" ? 1 : 0
                visible: opacity > 0
                Behavior on opacity {
                    NumberAnimation { duration: root.viewTransitionsEnabled ? 210 : 0 }
                }
                onCurrentIndexChanged: {
                    if (root.gameViewMode === "list")
                        root.positionGameListAtIndex(currentIndex)
                    if (root.previewReady && root.page === "games") {
                        root.activateGamePreview()
                    }
                }

                delegate: Item {
                    id: gameCard
                    property bool isSelected: ListView.isCurrentItem
                    property var game: root.gameAtDisplayIndex(index)
                    property real coverAspect: gameCover.status === Image.Ready &&
                            gameCover.sourceSize.height > 0 ?
                            gameCover.sourceSize.width / gameCover.sourceSize.height : 0.72
                    // Contain every aspect ratio inside the artwork viewport;
                    // the selected-card scale must stay below the sort row.
                    property real boxHeight: Math.min(318, 226 / coverAspect)
                    property real boxWidth: boxHeight * coverAspect
                    width: 250
                    height: 478
                    scale: ListView.isCurrentItem ? 1.08 : 0.91
                    opacity: ListView.isCurrentItem ? 1.0 : 0.82
                    Behavior on scale { NumberAnimation { duration: 230; easing.type: Easing.OutCubic } }
                    Behavior on opacity { NumberAnimation { duration: 180 } }

                    Image {
                        id: gameCover
                        x: (parent.width - parent.boxWidth) / 2
                        y: 12 + (318 - parent.boxHeight) / 2
                        width: parent.boxWidth
                        height: parent.boxHeight
                        source: game ? (game.assets.boxFront || "") : ""
                        fillMode: Image.PreserveAspectFit
                        asynchronous: true
                        smooth: true
                        mipmap: true
                    }

                    Rectangle {
                        x: 12
                        y: 338
                        width: 36
                        height: 3
                        color: root.accent
                    }

                    Text {
                        x: 12
                        y: 354
                        width: parent.width - 24
                        height: 46
                        text: root.displayTitle(game)
                        color: "#f1f3f8"
                        wrapMode: Text.Wrap
                        maximumLineCount: 2
                        elide: Text.ElideRight
                        font.family: global.fonts.sans
                        font.pixelSize: gameCard.isSelected ? 22 : 19
                        font.weight: Font.Bold
                        style: Text.Outline
                        styleColor: "#d0000000"
                    }

                    Text {
                        x: 12
                        y: 416
                        width: parent.width - 24
                        height: 36
                        text: root.scoreText(game) + "\nRELEASE  " + root.releaseYear(game)
                        color: root.accent
                        wrapMode: Text.Wrap
                        maximumLineCount: 2
                        fontSizeMode: Text.HorizontalFit
                        minimumPixelSize: 12
                        font.family: global.fonts.sans
                        font.pixelSize: 15
                        font.weight: Font.Bold
                        font.letterSpacing: 0
                        style: Text.Outline
                        styleColor: "#d0000000"
                    }

                    MouseArea {
                        anchors.fill: parent
                        pressAndHoldInterval: 800
                        onPressAndHold: root.openGameActions(game, index)
                        onClicked: {
                            if (root.gameActionOpen) return
                            gameRail.currentIndex = index
                            root.launch(game)
                        }
                    }
                }
            }

            Item {
                id: listViewPanel
                x: 48
                y: !root.dualScreenDevice ? 484 : 324
                width: parent.width - 96
                // Show only complete rows and stop above the persistent footer:
                // eight rows on Thor, six beside a single-screen PIP.
                height: !root.dualScreenDevice ? 476 : 636
                opacity: root.gameViewMode === "list" ? 1 : 0
                visible: opacity > 0
                Behavior on opacity {
                    NumberAnimation { duration: root.viewTransitionsEnabled ? 210 : 0 }
                }

                Item {
                    id: selectedGameArtPanel
                    property bool swappedSingleScreen: !root.dualScreenDevice &&
                            root.singleScreenMediaSwapped
                    x: swappedSingleScreen ?
                       root.width - 624 - 56 - listViewPanel.x : 0
                    // The Flip's list begins lower to reserve room for PIP,
                    // but its cover should not begin with that list. Give the
                    // single-screen cover its own square viewport between the
                    // sort controls and Android's usable bottom edge. A square
                    // cover then receives exactly 18 px on all four sides.
                    // Thor: the sort row ends at y=394 and this parent begins
                    // at y=324, so 90 px provides a real gap before artwork.
                    // The Flip retains its separate single-screen correction.
                    y: swappedSingleScreen ? 102 - listViewPanel.y :
                       (!root.dualScreenDevice ? -76 : 90)
                    width: swappedSingleScreen ? 624 : 600
                    height: swappedSingleScreen ? Math.round(624 * 9 / 16) :
                            (!root.dualScreenDevice ? 540 : parent.height - 90)
                    // Use equal outer padding on every side and center within
                    // the entire list panel. The former 106 px top inset made
                    // every cover appear visibly low on a single-screen Flip.
                    property real coverWidth: Math.max(1, width - 36)
                    property real coverHeight: Math.max(1, height - 36)

                    Image {
                        id: listCover
                        anchors.centerIn: parent
                        width: parent.coverWidth
                        height: parent.coverHeight
                        source: root.boxArtworkSourceA
                        fillMode: Image.PreserveAspectFit
                        asynchronous: false
                        cache: true
                        smooth: true
                        mipmap: true
                        opacity: root.boxArtworkSlot === 0 ? 1 : 0
                    }

                    Image {
                        anchors.centerIn: parent
                        width: parent.coverWidth
                        height: parent.coverHeight
                        source: root.boxArtworkSourceB
                        fillMode: Image.PreserveAspectFit
                        asynchronous: false
                        cache: true
                        smooth: true
                        mipmap: true
                        opacity: root.boxArtworkSlot === 1 ? 1 : 0
                    }

                    Text {
                        anchors.centerIn: parent
                        width: parent.width - 80
                        visible: root.activeGame && !root.activeGame.assets.boxFront
                        text: root.activeGame ? root.displayTitle(root.activeGame) : ""
                        color: "#e8ebf2"
                        horizontalAlignment: Text.AlignHCenter
                        wrapMode: Text.Wrap
                        font.family: global.fonts.condensed
                        font.pixelSize: 44
                        font.weight: Font.DemiBold
                    }
                }

                ListView {
                    id: gameListRail
                    x: 634
                    y: 0
                    width: parent.width - x
                    height: parent.height
                    orientation: ListView.Vertical
                    model: activeGameModel
                    currentIndex: gameRail.currentIndex
                    spacing: 4
                    clip: true
                    cacheBuffer: height * 2
                    snapMode: ListView.SnapToItem
                    boundsBehavior: Flickable.StopAtBounds
                    focus: false
                    keyNavigationEnabled: false
                    highlightMoveDuration: 100
                    highlightRangeMode: ListView.ApplyRange
                    // Eight complete 76 px rows plus seven 4 px gaps exactly
                    // fill this viewport. Put the selected row on a 80 px
                    // stride so centering can never expose half a row.
                    preferredHighlightBegin: 240
                    preferredHighlightEnd: 240

                    delegate: Rectangle {
                        id: gameListRow
                        property bool isSelected: ListView.isCurrentItem
                        property var game: root.gameAtDisplayIndex(index)
                        width: gameListRail.width
                        height: 76
                        color: "transparent"
                        clip: true
                        border.width: ListView.isCurrentItem ? 2 : 1
                        border.color: ListView.isCurrentItem ?
                                      Qt.lighter(root.accent, 1.18) : "#38ffffff"
                        radius: 7

                        ShaderEffectSource {
                            id: rowGlassSource
                            anchors.fill: parent
                            sourceItem: root.upperArtworkSlot === 0 ? upperArtworkA : upperArtworkB
                            sourceRect: Qt.rect(listViewPanel.x + gameListRail.x - 28,
                                                listViewPanel.y + gameListRow.y -
                                                gameListRail.contentY - 28,
                                                gameListRow.width + 56,
                                                gameListRow.height + 56)
                            textureSize: Qt.size(gameListRow.width + 56,
                                                 gameListRow.height + 56)
                            live: true
                            smooth: true
                            visible: false
                        }

                        Rectangle {
                            anchors.fill: parent
                            visible: !root.liquidGlassEnabled
                            color: gameListRow.isSelected ?
                                   Qt.rgba(root.accent.r, root.accent.g,
                                           root.accent.b, 0.92) :
                                   (index % 2 === 0 ? "#78060a10" : "#86060a10")
                            radius: 7
                        }

                        ShaderEffect {
                            anchors.fill: parent
                            visible: root.liquidGlassEnabled
                            property variant source: rowGlassSource
                            property size glassSize: Qt.size(width, height)
                            property real cornerRadius: 7
                            property real edgeThickness: 23
                            property real distortionStrength: 20.0
                            property real scatterRadius: 4.2
                            property real samplePadding: 28
                            property color glassTint: gameListRow.isSelected ?
                                Qt.rgba(root.accent.r, root.accent.g, root.accent.b, 0.86) :
                                (index % 2 === 0 ? Qt.rgba(0.025, 0.04, 0.07, 0.40) :
                                                  Qt.rgba(0.025, 0.04, 0.07, 0.45))
                            fragmentShader: root.liquidGlassFragmentShader
                        }

                        Rectangle {
                            anchors.fill: parent
                            anchors.margins: gameListRow.isSelected ? 2 : 1
                            color: "transparent"
                            border.width: 1
                            border.color: gameListRow.isSelected ? "#46ffffff" : "#20ffffff"
                            radius: 5
                        }

                        Text {
                            x: 20
                            anchors.verticalCenter: parent.verticalCenter
                            width: 54
                            text: (index < 9 ? "0" : "") + (index + 1)
                            color: gameListRow.isSelected ? "#03050a" : "#647087"
                            font.family: global.fonts.condensed
                            font.pixelSize: 19
                            font.weight: Font.Bold
                        }

                        Text {
                            x: 78
                            anchors.verticalCenter: parent.verticalCenter
                            width: parent.width - 590
                            text: root.displayTitle(game)
                            color: gameListRow.isSelected ? "#03050a" : "#eef1f6"
                            elide: Text.ElideRight
                            font.family: global.fonts.sans
                            font.pixelSize: 24
                            font.weight: gameListRow.isSelected ? Font.Bold : Font.DemiBold
                        }

                        Text {
                            anchors.right: parent.right
                            anchors.rightMargin: 22
                            anchors.verticalCenter: parent.verticalCenter
                            width: 480
                            text: root.gameFactsText(game)
                            color: gameListRow.isSelected ? "#03050a" : root.accent
                            horizontalAlignment: Text.AlignRight
                            elide: Text.ElideRight
                            font.family: global.fonts.sans
                            font.pixelSize: 19
                            font.weight: Font.Bold
                            font.letterSpacing: 0.1
                        }

                        MouseArea {
                            anchors.fill: parent
                            pressAndHoldInterval: 800
                            onPressAndHold: root.openGameActions(game, index)
                            onClicked: {
                                if (root.gameActionOpen) return
                                gameRail.currentIndex = index
                                root.launch(game)
                            }
                        }
                    }
                }
            }

            Rectangle {
                z: 80
                x: 48
                y: 966
                width: parent.width - 96
                height: 58
                color: "#dc07090d"
            }

            Text {
                z: 81
                anchors.right: parent.right
                anchors.rightMargin: 62
                y: 986
                text: "L1 / R1  SORT     L2 / R2  SYSTEM     LEFT / RIGHT  PAGE     Y  VIEW: " + root.gameViewMode.toUpperCase() +
                      "     X  SETTINGS     RIGHT STICK  VIEWS     B  BACK     A  PLAY     HOLD GAME  OPTIONS"
                color: "#7f899c"
                font.family: global.fonts.sans
                font.pixelSize: 15
                font.letterSpacing: 1
            }
        }
    }

    Text {
        id: displaySettingsHint
        z: 90
        anchors.right: parent.right
        anchors.rightMargin: 62
        y: 998
        visible: root.page === "home" && !root.settingsOpen
        text: root.homeViewMode === "list" ?
              "L1 / R1  CATEGORY     L2 / R2  SYSTEM     LEFT / RIGHT  PAGE     UP / DOWN  SELECT     Y  LAYOUT     X  SETTINGS     RIGHT STICK  VIEWS     B  SYSTEM LIST     A  PLAY" :
              "L1 / R1  CATEGORY     L2 / R2  SYSTEM     Y  LAYOUT     X  SETTINGS     D-PAD  NAVIGATE     RIGHT STICK  VIEWS     A  OPEN"
        color: "#7f899c"
        font.family: global.fonts.sans
        font.pixelSize: 15
        font.letterSpacing: 1

        MouseArea {
            anchors.fill: parent
            anchors.margins: -18
            onClicked: {
                root.settingsOpen = true
                root.settingsIndex = 0
            }
        }
    }

    Text {
        z: 90
        anchors.left: parent.left
        anchors.leftMargin: 62
        y: root.page === "games" ? 986 : 998
        visible: !root.settingsOpen
        text: root.currentViewName
        color: "#7f899c"
        font.family: global.fonts.sans
        font.pixelSize: 15
        font.weight: Font.DemiBold
        font.letterSpacing: 1
    }

    Rectangle {
        id: updatePromptOverlay
        z: 850
        anchors.fill: parent
        visible: root.updatePromptOpen
        color: "#db04070c"

        MouseArea { anchors.fill: parent }

        Rectangle {
            anchors.centerIn: parent
            width: 760
            height: 390
            color: "#f20a0e16"
            border.width: 2
            border.color: root.accent
            radius: 12

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                y: 54
                text: "LUCENT UPDATE READY"
                color: "white"
                font.family: global.fonts.condensed
                font.pixelSize: 48
                font.weight: Font.Bold
                font.letterSpacing: 1.4
            }

            Text {
                x: 60
                y: 130
                width: parent.width - 120
                text: root.updateStatusMessage !== "" ? root.updateStatusMessage :
                      "A new signed Lucent package has been downloaded."
                color: "#b8c1d1"
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                font.family: global.fonts.sans
                font.pixelSize: 20
            }

            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                y: 238
                spacing: 24

                Repeater {
                    model: ["INSTALL", "LATER"]
                    Rectangle {
                        width: 270
                        height: 82
                        color: root.updatePromptChoice === index ? root.accent : "#7e121925"
                        border.width: root.updatePromptChoice === index ? 3 : 1
                        border.color: root.updatePromptChoice === index ?
                                      Qt.lighter(root.accent, 1.18) : "#42ffffff"
                        radius: 8

                        Text {
                            anchors.centerIn: parent
                            text: modelData
                            color: root.updatePromptChoice === index ? "#05070b" : "white"
                            font.family: global.fonts.sans
                            font.pixelSize: 24
                            font.weight: Font.Bold
                            font.letterSpacing: 1.4
                        }

                        MouseArea {
                            anchors.fill: parent
                            onClicked: {
                                root.updatePromptChoice = index
                                if (index === 0) root.installReadyUpdate()
                                else root.dismissReadyUpdate()
                            }
                        }
                    }
                }
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                y: 344
                text: "LEFT / RIGHT  CHOOSE     A  CONFIRM     B  LATER"
                color: "#7f899c"
                font.family: global.fonts.sans
                font.pixelSize: 14
                font.letterSpacing: 1
            }
        }
    }

    Rectangle {
        id: gameActionOverlay
        z: 700
        anchors.fill: parent
        visible: root.gameActionOpen
        color: "#e805080d"

        MouseArea {
            anchors.fill: parent
            onClicked: root.closeGameActions()
        }

        Rectangle {
            id: gameActionPanel
            anchors.centerIn: parent
            width: 820
            height: 570
            color: "#fb0d121a"
            border.width: 2
            border.color: root.accent
            radius: 10

            // Swallow taps on empty dialog space; only the dimmed area outside
            // the panel dismisses it. Controls declared below remain on top.
            MouseArea { anchors.fill: parent }

            Text {
                x: 44
                y: 38
                width: parent.width - 88
                text: root.displayTitle(root.gameActionGame)
                color: "white"
                elide: Text.ElideRight
                font.family: global.fonts.condensed
                font.pixelSize: 40
                font.weight: Font.Bold
            }

            Text {
                x: 46
                y: 94
                text: root.gameActionMode === "menu" ? "GAME OPTIONS" :
                      root.gameActionMode === "rename" ? "RENAME GAME" :
                      root.gameActionMode === "confirm-remove" ? "CONFIRM REMOVE FROM LIST" :
                      root.gameActionMode === "confirm-delete" ? "CONFIRM DELETE" : "LUCENT LIBRARY"
                color: root.accent
                font.family: global.fonts.sans
                font.pixelSize: 17
                font.weight: Font.Bold
                font.letterSpacing: 2
            }

            Item {
                anchors.fill: parent
                visible: root.gameActionMode === "menu"

                Rectangle {
                    x: 44
                    y: 136
                    width: parent.width - 88
                    height: 92
                    color: root.gameActionIndex === 0 ? root.accent : "#b3121822"
                    border.width: root.gameActionIndex === 0 ? 2 : 1
                    border.color: root.gameActionIndex === 0 ? Qt.lighter(root.accent, 1.18) : "#42ffffff"
                    radius: 7

                    Text {
                        anchors.centerIn: parent
                        text: "RENAME"
                        color: root.gameActionIndex === 0 ? "#05070b" : "white"
                        font.family: global.fonts.sans
                        font.pixelSize: 26
                        font.weight: Font.Bold
                        font.letterSpacing: 1.2
                    }
                    MouseArea {
                        anchors.fill: parent
                        onClicked: { root.gameActionIndex = 0; root.beginRenameGame() }
                    }
                }

                Rectangle {
                    x: 44
                    y: 242
                    width: parent.width - 88
                    height: 92
                    visible: root.gameActionAllowsRemoveFromList()
                    color: root.gameActionIndex === 1 ? root.accent : "#b3121822"
                    border.width: root.gameActionIndex === 1 ? 2 : 1
                    border.color: root.gameActionIndex === 1 ? Qt.lighter(root.accent, 1.18) : "#42ffffff"
                    radius: 7

                    Text {
                        anchors.centerIn: parent
                        text: "REMOVE FROM " + root.homeShelfName(root.gameActionCategory)
                        color: root.gameActionIndex === 1 ? "#05070b" : "white"
                        font.family: global.fonts.sans
                        font.pixelSize: 24
                        font.weight: Font.Bold
                        font.letterSpacing: 1.2
                    }
                    MouseArea {
                        anchors.fill: parent
                        onClicked: { root.gameActionIndex = 1; root.gameActionMode = "confirm-remove" }
                    }
                }

                Rectangle {
                    property int deleteIndex: root.gameActionAllowsRemoveFromList() ? 2 : 1
                    x: 44
                    y: root.gameActionAllowsRemoveFromList() ? 348 : 242
                    width: parent.width - 88
                    height: 92
                    color: root.gameActionIndex === deleteIndex ? "#ff6d70" : "#b3121822"
                    border.width: root.gameActionIndex === deleteIndex ? 2 : 1
                    border.color: root.gameActionIndex === deleteIndex ? "#ff9b9d" : "#42ffffff"
                    radius: 7

                    Text {
                        anchors.centerIn: parent
                        text: "DELETE ROM FILE"
                        color: root.gameActionIndex === parent.deleteIndex ? "#120405" : "white"
                        font.family: global.fonts.sans
                        font.pixelSize: 24
                        font.weight: Font.Bold
                        font.letterSpacing: 1.2
                    }
                    MouseArea {
                        anchors.fill: parent
                        onClicked: {
                            root.gameActionIndex = parent.deleteIndex
                            root.gameActionMode = "confirm-delete"
                        }
                    }
                }

                Text {
                    anchors.horizontalCenter: parent.horizontalCenter
                    y: 492
                    text: "TAP AN OPTION  •  B TO CANCEL"
                    color: "#8e98aa"
                    font.family: global.fonts.sans
                    font.pixelSize: 15
                    font.letterSpacing: 1.4
                }
            }

            Item {
                anchors.fill: parent
                visible: root.gameActionMode === "confirm-remove"

                Text {
                    x: 54
                    y: 164
                    width: parent.width - 108
                    text: "Hide this game from " + root.homeShelfName(root.gameActionCategory) +
                          " only? The ROM file and complete library entries will remain untouched."
                    color: "#e9edf4"
                    wrapMode: Text.Wrap
                    horizontalAlignment: Text.AlignHCenter
                    font.family: global.fonts.sans
                    font.pixelSize: 24
                }

                Row {
                    anchors.horizontalCenter: parent.horizontalCenter
                    y: 324
                    spacing: 20
                    Rectangle {
                        width: 330; height: 82; color: "#b3121822"; border.width: 1
                        border.color: "#52ffffff"; radius: 7
                        Text { anchors.centerIn: parent; text: "CANCEL"; color: "white"; font.pixelSize: 23; font.bold: true }
                        MouseArea { anchors.fill: parent; onClicked: root.gameActionMode = "menu" }
                    }
                    Rectangle {
                        width: 330; height: 82; color: root.accent; radius: 7
                        Text { anchors.centerIn: parent; text: "REMOVE FROM LIST"; color: "#05070b"; font.pixelSize: 22; font.bold: true }
                        MouseArea { anchors.fill: parent; onClicked: root.submitRemoveFromList() }
                    }
                }
            }

            Item {
                anchors.fill: parent
                visible: root.gameActionMode === "rename"

                Rectangle {
                    x: 44
                    y: 164
                    width: parent.width - 88
                    height: 72
                    color: "#d9080c13"
                    border.width: 2
                    border.color: root.accent
                    radius: 7

                    TextInput {
                        id: renameField
                        anchors.fill: parent
                        anchors.leftMargin: 22
                        anchors.rightMargin: 22
                        verticalAlignment: TextInput.AlignVCenter
                        color: "white"
                        selectionColor: root.accent
                        selectedTextColor: "#05070b"
                        font.family: global.fonts.sans
                        font.pixelSize: 25
                        selectByMouse: true
                        inputMethodHints: Qt.ImhNoPredictiveText
                        onAccepted: root.submitRenameGame()
                    }
                }

                Rectangle {
                    x: 44
                    y: 272
                    width: parent.width - 88
                    height: 84
                    color: root.accent
                    radius: 7
                    Text {
                        anchors.centerIn: parent
                        text: "SAVE NAME"
                        color: "#05070b"
                        font.family: global.fonts.sans
                        font.pixelSize: 24
                        font.weight: Font.Bold
                    }
                    MouseArea { anchors.fill: parent; onClicked: root.submitRenameGame() }
                }
            }

            Item {
                anchors.fill: parent
                visible: root.gameActionMode === "confirm-delete"

                Text {
                    x: 54
                    y: 164
                    width: parent.width - 108
                    text: "Permanently delete the actual ROM/game file from device storage and remove it from Lucent? This cannot be undone."
                    color: "#e9edf4"
                    wrapMode: Text.Wrap
                    horizontalAlignment: Text.AlignHCenter
                    font.family: global.fonts.sans
                    font.pixelSize: 24
                }

                Row {
                    anchors.horizontalCenter: parent.horizontalCenter
                    y: 294
                    spacing: 20
                    Rectangle {
                        width: 330; height: 82; color: "#b3121822"; border.width: 1
                        border.color: "#52ffffff"; radius: 7
                        Text { anchors.centerIn: parent; text: "CANCEL"; color: "white"; font.pixelSize: 23; font.bold: true }
                        MouseArea { anchors.fill: parent; onClicked: root.gameActionMode = "menu" }
                    }
                    Rectangle {
                        width: 330; height: 82; color: "#ff6d70"; radius: 7
                        Text { anchors.centerIn: parent; text: "DELETE GAME"; color: "#150405"; font.pixelSize: 23; font.bold: true }
                        MouseArea { anchors.fill: parent; onClicked: root.submitDeleteGame() }
                    }
                }
            }

            Item {
                anchors.fill: parent
                visible: root.gameActionMode === "working" ||
                         root.gameActionMode === "success" || root.gameActionMode === "error"
                Text {
                    anchors.centerIn: parent
                    width: parent.width - 110
                    text: root.gameActionMessage
                    color: root.gameActionMode === "error" ? "#ff8588" : root.accent
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    font.family: global.fonts.condensed
                    font.pixelSize: 34
                    font.weight: Font.Bold
                }
                MouseArea {
                    anchors.fill: parent
                    enabled: root.gameActionMode !== "working"
                    onClicked: root.closeGameActions()
                }
            }
        }
    }

    Rectangle {
        id: settingsOverlay
        z: 500
        anchors.fill: parent
        visible: root.settingsOpen
        color: "#e905080d"

        // Four explicit dismissal zones avoid Qt's full-screen mouse catcher
        // competing with the modal and its row controls on touch devices.
        Item {
            z: 1
            anchors.fill: parent

            MouseArea {
                x: 0; y: 0
                width: parent.width; height: settingsPanel.y
                onClicked: { root.settingsOpen = false; root.forceActiveFocus() }
            }
            MouseArea {
                x: 0; y: settingsPanel.y + settingsPanel.height
                width: parent.width; height: parent.height - y
                onClicked: { root.settingsOpen = false; root.forceActiveFocus() }
            }
            MouseArea {
                x: 0; y: settingsPanel.y
                width: settingsPanel.x; height: settingsPanel.height
                onClicked: { root.settingsOpen = false; root.forceActiveFocus() }
            }
            MouseArea {
                x: settingsPanel.x + settingsPanel.width; y: settingsPanel.y
                width: parent.width - x; height: settingsPanel.height
                onClicked: { root.settingsOpen = false; root.forceActiveFocus() }
            }
        }

        Rectangle {
            id: settingsPanel
            z: 2
            anchors.centerIn: parent
            width: 980
            height: 960
            color: "#f20d121a"
            border.width: 1
            border.color: root.accent
            radius: 8

            // Consume taps inside the modal itself. Individual setting rows,
            // declared later, remain above this catcher and keep their own
            // actions; only the dimmed area outside dismisses the sheet.
            MouseArea { anchors.fill: parent }

            Text {
                x: 48
                y: 38
                text: "DISPLAY, LIBRARY & LIGHTING"
                color: "white"
                font.family: global.fonts.condensed
                font.pixelSize: 42
                font.weight: Font.Bold
                font.letterSpacing: 2
            }

            Text {
                x: 50
                y: 94
                text: "LUCENT " + root.lucentVersion +
                      "  •  Display changes apply instantly; updates run automatically at startup"
                color: "#9da7b8"
                font.family: global.fonts.sans
                font.pixelSize: 17
            }

            // Keep the legacy row declarations inert for source compatibility;
            // the compact settings sheet below is the active settings UI.
            Item {
                visible: false
                anchors.fill: parent

            Rectangle {
                x: 44
                y: 150
                width: parent.width - 88
                height: 112
                color: root.settingsIndex === 0 ? "#261f2a38" : "#9b111720"
                border.width: root.settingsIndex === 0 ? 2 : 1
                border.color: root.settingsIndex === 0 ? root.accent : "#30ffffff"
                radius: 5

                Text {
                    x: 28
                    y: 22
                    text: "SYSTEM WALLPAPER MODE"
                    color: "white"
                    font.family: global.fonts.sans
                    font.pixelSize: 21
                    font.weight: Font.Bold
                    font.letterSpacing: 1
                }
                Text {
                    x: 28
                    y: 60
                    text: "Preloaded static angle; rerolls only after you leave"
                    color: "#9da7b8"
                    font.family: global.fonts.sans
                    font.pixelSize: 16
                }
                Text {
                    anchors.right: parent.right
                    anchors.rightMargin: 28
                    anchors.verticalCenter: parent.verticalCenter
                    text: "STATIC"
                    color: root.accent
                    font.family: global.fonts.condensed
                    font.pixelSize: 30
                    font.weight: Font.Bold
                }
                MouseArea {
                    anchors.fill: parent
                    onClicked: { root.settingsIndex = 0; root.activateSetting(0) }
                }
            }

            Rectangle {
                x: 44
                y: 278
                width: parent.width - 88
                height: 112
                color: root.settingsIndex === 1 ? "#261f2a38" : "#9b111720"
                border.width: root.settingsIndex === 1 ? 2 : 1
                border.color: root.settingsIndex === 1 ? root.accent : "#30ffffff"
                radius: 5

                Text {
                    x: 28
                    y: 22
                    text: "GAME PREVIEW PLACEMENT"
                    color: "white"
                    font.family: global.fonts.sans
                    font.pixelSize: 21
                    font.weight: Font.Bold
                    font.letterSpacing: 1
                }
                Text {
                    x: 28
                    y: 60
                    text: "Automatic detects the screen count; choose PIP or Off manually"
                    color: "#9da7b8"
                    font.family: global.fonts.sans
                    font.pixelSize: 16
                }
                Text {
                    anchors.right: parent.right
                    anchors.rightMargin: 28
                    anchors.verticalCenter: parent.verticalCenter
                    text: root.previewPlacementLabel()
                    color: root.accent
                    font.family: global.fonts.condensed
                    font.pixelSize: 30
                    font.weight: Font.Bold
                }
                MouseArea {
                    anchors.fill: parent
                    onClicked: { root.settingsIndex = 1; root.activateSetting(1) }
                }
            }

            Rectangle {
                x: 44
                y: 406
                width: parent.width - 88
                height: 112
                color: root.settingsIndex === 2 ? "#261f2a38" : "#9b111720"
                border.width: root.settingsIndex === 2 ? 2 : 1
                border.color: root.settingsIndex === 2 ? root.accent : "#30ffffff"
                radius: 5

                Text {
                    x: 28
                    y: 22
                    text: "PREVIEW VIDEO SOUND"
                    color: "white"
                    font.family: global.fonts.sans
                    font.pixelSize: 21
                    font.weight: Font.Bold
                    font.letterSpacing: 1
                }
                Text {
                    x: 28
                    y: 60
                    text: "Sound follows the visible preview; preloaded neighbors stay silent"
                    color: "#9da7b8"
                    font.family: global.fonts.sans
                    font.pixelSize: 16
                }
                Text {
                    anchors.right: parent.right
                    anchors.rightMargin: 28
                    anchors.verticalCenter: parent.verticalCenter
                    text: root.previewSoundEnabled ? "ON" : "OFF"
                    color: root.accent
                    font.family: global.fonts.condensed
                    font.pixelSize: 30
                    font.weight: Font.Bold
                }
                MouseArea {
                    anchors.fill: parent
                    onClicked: { root.settingsIndex = 2; root.activateSetting(0) }
                }
            }

            Rectangle {
                x: 44
                y: 534
                width: parent.width - 88
                height: 112
                color: root.settingsIndex === 3 ? "#261f2a38" : "#9b111720"
                border.width: root.settingsIndex === 3 ? 2 : 1
                border.color: root.settingsIndex === 3 ? root.accent : "#30ffffff"
                radius: 5

                Text {
                    x: 28
                    y: 22
                    text: "LIQUID GLASS"
                    color: "white"
                    font.family: global.fonts.sans
                    font.pixelSize: 21
                    font.weight: Font.Bold
                    font.letterSpacing: 1
                }
                Text {
                    x: 28
                    y: 60
                    text: "Optional refractive blur and wallpaper distortion for controls"
                    color: "#9da7b8"
                    font.family: global.fonts.sans
                    font.pixelSize: 16
                }
                Text {
                    anchors.right: parent.right
                    anchors.rightMargin: 28
                    anchors.verticalCenter: parent.verticalCenter
                    text: root.liquidGlassEnabled ? "ON" : "OFF"
                    color: root.accent
                    font.family: global.fonts.condensed
                    font.pixelSize: 30
                    font.weight: Font.Bold
                }
                MouseArea {
                    anchors.fill: parent
                    onClicked: { root.settingsIndex = 3; root.activateSetting(0) }
                }
            }

            Rectangle {
                x: 44
                y: 662
                width: parent.width - 88
                height: 112
                color: root.settingsIndex === 4 ? "#261f2a38" : "#9b111720"
                border.width: root.settingsIndex === 4 ? 2 : 1
                border.color: root.settingsIndex === 4 ? root.accent : "#30ffffff"
                radius: 5

                Text {
                    x: 28
                    y: 22
                    text: "SYSTEM-MATCHED STICK LEDS"
                    color: "white"
                    font.family: global.fonts.sans
                    font.pixelSize: 21
                    font.weight: Font.Bold
                    font.letterSpacing: 1
                }
                Text {
                    x: 28
                    y: 60
                    text: "Uses AYN brightness by default; left / right selects manual 1–100%"
                    color: "#9da7b8"
                    font.family: global.fonts.sans
                    font.pixelSize: 16
                }

                Rectangle {
                    anchors.right: ledBrightnessValue.left
                    anchors.rightMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    width: 48
                    height: 48
                    color: "#221f2a38"
                    border.width: 1
                    border.color: "#45ffffff"
                    radius: 24

                    Text {
                        anchors.centerIn: parent
                        text: "−"
                        color: "white"
                        font.family: global.fonts.sans
                        font.pixelSize: 27
                        font.weight: Font.Bold
                    }
                    MouseArea {
                        anchors.fill: parent
                        onClicked: {
                            root.settingsIndex = 4
                            var base = root.systemLedUseDeviceBrightness ? 2 :
                                       root.systemLedBrightness
                            root.setSystemLedBrightness(base - 1)
                        }
                    }
                }

                Text {
                    id: ledBrightnessValue
                    anchors.right: ledBrightnessPlus.left
                    anchors.rightMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    width: 124
                    horizontalAlignment: Text.AlignHCenter
                    text: !root.systemLedEnabled ? "OFF" :
                          (root.systemLedUseDeviceBrightness ? "ON  SYSTEM" :
                           "ON  " + root.systemLedBrightness + "%")
                    color: root.accent
                    font.family: global.fonts.condensed
                    font.pixelSize: 27
                    font.weight: Font.Bold
                    MouseArea {
                        anchors.fill: parent
                        onClicked: {
                            root.settingsIndex = 4
                            root.setSystemLedUseDeviceBrightness(true)
                        }
                    }
                }

                Rectangle {
                    id: ledBrightnessPlus
                    anchors.right: parent.right
                    anchors.rightMargin: 24
                    anchors.verticalCenter: parent.verticalCenter
                    width: 48
                    height: 48
                    color: "#221f2a38"
                    border.width: 1
                    border.color: "#45ffffff"
                    radius: 24

                    Text {
                        anchors.centerIn: parent
                        text: "+"
                        color: "white"
                        font.family: global.fonts.sans
                        font.pixelSize: 27
                        font.weight: Font.Bold
                    }
                    MouseArea {
                        anchors.fill: parent
                        onClicked: {
                            root.settingsIndex = 4
                            var base = root.systemLedUseDeviceBrightness ? 9 :
                                       root.systemLedBrightness
                            root.setSystemLedBrightness(base + 1)
                        }
                    }
                }
                MouseArea {
                    x: 0
                    y: 0
                    width: parent.width - 270
                    height: parent.height
                    onClicked: { root.settingsIndex = 4; root.activateSetting(0) }
                }
            }

            Rectangle {
                x: 44
                y: 790
                width: parent.width - 88
                height: 112
                color: root.settingsIndex === 5 ? "#261f2a38" : "#9b111720"
                border.width: root.settingsIndex === 5 ? 2 : 1
                border.color: root.settingsIndex === 5 ? root.accent : "#30ffffff"
                radius: 5

                Text {
                    x: 28
                    y: 22
                    text: "UPDATE LIBRARY & LUCENT"
                    color: "white"
                    font.family: global.fonts.sans
                    font.pixelSize: 21
                    font.weight: Font.Bold
                    font.letterSpacing: 1
                }
                Text {
                    x: 28
                    y: 60
                    text: "Scan games and check GitHub for app and theme updates"
                    color: "#9da7b8"
                    font.family: global.fonts.sans
                    font.pixelSize: 16
                }
                Text {
                    anchors.right: parent.right
                    anchors.rightMargin: 28
                    anchors.verticalCenter: parent.verticalCenter
                    text: "RUN"
                    color: root.accent
                    font.family: global.fonts.condensed
                    font.pixelSize: 30
                    font.weight: Font.Bold
                }
                MouseArea {
                    anchors.fill: parent
                    onClicked: { root.settingsIndex = 5; root.activateSetting(0) }
                }
            }

            }

            Text {
                anchors.right: parent.right
                anchors.rightMargin: 48
                y: 44
                text: "PAGE " + (root.settingsPage + 1) + " / " +
                      Math.ceil(root.settingsOptionCount / root.settingsPageSize)
                color: root.accent
                font.family: global.fonts.sans
                font.pixelSize: 16
                font.weight: Font.Bold
                font.letterSpacing: 1.2
            }

            Repeater {
                // Six spacious rows per page. Up/Down crosses page boundaries
                // automatically, so touch and controller users get one unified
                // settings surface without compressed text or tiny targets.
                model: root.settingsPageSize

                Rectangle {
                    property int setting: root.settingsPage * root.settingsPageSize + index
                    visible: setting < root.settingsOptionCount
                    x: 44
                    y: 148 + index * 118
                    width: settingsPanel.width - 88
                    height: 106
                    color: root.settingsIndex === setting ? "#261f2a38" : "#9b111720"
                    border.width: root.settingsIndex === setting ? 2 : 1
                    border.color: root.settingsIndex === setting ? root.accent : "#30ffffff"
                    radius: 7

                    Text {
                        x: 24
                        y: 18
                        text: root.settingTitle(parent.setting)
                        color: "white"
                        font.family: global.fonts.sans
                        font.pixelSize: 19
                        font.weight: Font.Bold
                        font.letterSpacing: 0.9
                    }

                    Text {
                        x: 24
                        y: 57
                        width: parent.width - 370
                        text: root.settingDescription(parent.setting)
                        color: "#a7b0c1"
                        elide: Text.ElideRight
                        font.family: global.fonts.sans
                        font.pixelSize: 14
                    }

                    Text {
                        anchors.right: parent.right
                        anchors.rightMargin: parent.setting === 7 ? 92 : 24
                        anchors.verticalCenter: parent.verticalCenter
                        width: parent.setting === 8 ? 340 : 286
                        horizontalAlignment: Text.AlignRight
                        text: root.settingValue(parent.setting)
                        color: root.accent
                        elide: Text.ElideRight
                        font.family: global.fonts.condensed
                        font.pixelSize: parent.setting === 8 ? 23 : 27
                        font.weight: Font.Bold
                    }

                    Rectangle {
                        visible: parent.setting === 7
                        anchors.right: parent.right
                        anchors.rightMargin: 24
                        anchors.verticalCenter: parent.verticalCenter
                        width: 48
                        height: 48
                        radius: 24
                        color: "#221f2a38"
                        border.width: 1
                        border.color: "#45ffffff"
                        Text { anchors.centerIn: parent; text: "+"; color: "white";
                               font.pixelSize: 27; font.bold: true }
                        MouseArea {
                            anchors.fill: parent
                            onClicked: {
                                root.settingsIndex = 7
                                root.setSystemLedBrightness(root.systemLedBrightness + 1)
                            }
                        }
                    }

                    Rectangle {
                        visible: parent.setting === 7
                        anchors.right: parent.right
                        anchors.rightMargin: 184
                        anchors.verticalCenter: parent.verticalCenter
                        width: 48
                        height: 48
                        radius: 24
                        color: "#221f2a38"
                        border.width: 1
                        border.color: "#45ffffff"
                        Text { anchors.centerIn: parent; text: "−"; color: "white";
                               font.pixelSize: 27; font.bold: true }
                        MouseArea {
                            anchors.fill: parent
                            onClicked: {
                                root.settingsIndex = 7
                                root.setSystemLedBrightness(root.systemLedBrightness - 1)
                            }
                        }
                    }

                    MouseArea {
                        x: 0
                        y: 0
                        width: parent.setting === 7 ? parent.width - 244 : parent.width
                        height: parent.height
                        onClicked: {
                            root.settingsIndex = parent.setting
                            root.activateSetting(0)
                        }
                    }
                }
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                y: 925
                text: "UP / DOWN  SELECT & PAGE     LEFT / RIGHT  CHANGE     B  CLOSE"
                color: "#7f899c"
                font.family: global.fonts.sans
                font.pixelSize: 15
                font.letterSpacing: 1
            }
        }
    }

    Rectangle {
        id: coverOrderEditorOverlay
        z: 815
        anchors.fill: parent
        visible: root.coverOrderEditorOpen
        color: "#e905080d"

        MouseArea { anchors.fill: parent }

        Rectangle {
            anchors.centerIn: parent
            width: 820
            height: 820
            color: "#f20d121a"
            border.width: 1
            border.color: root.accent
            radius: 8

            Text {
                x: 44; y: 34
                text: "COVER ROW ORDER"
                color: "white"
                font.family: global.fonts.condensed
                font.pixelSize: 40
                font.weight: Font.Bold
                font.letterSpacing: 2
            }

            Text {
                x: 46; y: 86
                text: "UP / DOWN SELECT  •  LEFT / RIGHT MOVE"
                color: "#9da7b8"
                font.family: global.fonts.sans
                font.pixelSize: 15
                font.letterSpacing: 1.3
            }

            Repeater {
                model: 8

                Rectangle {
                    property int orderZone: Number(root.coverRowOrder[index])
                    x: 42
                    y: 126 + index * 78
                    width: 736
                    height: 66
                    color: root.coverOrderEditorIndex === index ?
                           Qt.darker(root.accent, 3.6) : "#9b111720"
                    border.width: root.coverOrderEditorIndex === index ? 3 : 1
                    border.color: root.coverOrderEditorIndex === index ?
                                  root.accent : "#30ffffff"
                    radius: 5

                    Text {
                        x: 20
                        anchors.verticalCenter: parent.verticalCenter
                        text: (index < 9 ? "0" : "") + String(index + 1)
                        color: root.accent
                        font.family: global.fonts.condensed
                        font.pixelSize: 23
                        font.weight: Font.Bold
                    }

                    Text {
                        x: 82
                        anchors.verticalCenter: parent.verticalCenter
                        text: root.coverRowName(parent.orderZone)
                        color: "white"
                        font.family: global.fonts.sans
                        font.pixelSize: 21
                        font.weight: Font.Bold
                    }

                    Text {
                        anchors.right: parent.right
                        anchors.rightMargin: 22
                        anchors.verticalCenter: parent.verticalCenter
                        text: "←  MOVE  →"
                        color: root.coverOrderEditorIndex === index ?
                               "white" : "#778196"
                        font.family: global.fonts.sans
                        font.pixelSize: 16
                        font.weight: Font.DemiBold
                        font.letterSpacing: 1
                    }

                    MouseArea {
                        anchors.fill: parent
                        onClicked: root.coverOrderEditorIndex = index
                    }
                }
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                y: 770
                text: "B  BACK TO SETTINGS"
                color: "#7f899c"
                font.family: global.fonts.sans
                font.pixelSize: 15
                font.letterSpacing: 1
            }
        }
    }

    Rectangle {
        id: accentEditorOverlay
        z: 820
        anchors.fill: parent
        visible: root.accentEditorOpen
        color: "#e805080d"

        MouseArea { anchors.fill: parent }

        Rectangle {
            anchors.centerIn: parent
            width: 900
            height: 630
            color: "#fa0d121a"
            border.width: 2
            border.color: root.accentEditorColor()
            radius: 10

            Text {
                x: 48; y: 36
                text: "CUSTOM ACCENT COLORS"
                color: "white"
                font.family: global.fonts.condensed
                font.pixelSize: 40
                font.weight: Font.Bold
                font.letterSpacing: 2
            }
            Text {
                x: 50; y: 88
                text: root.accentGrouping === "system" ?
                      "Each installed system keeps its own color" :
                      "Systems share the color assigned to their platform family"
                color: "#9da7b8"
                font.family: global.fonts.sans
                font.pixelSize: 17
            }

            Rectangle {
                x: 596; y: 132; width: 248; height: 248
                color: root.accentEditorColor()
                radius: 124
                border.width: 4
                border.color: "#c8ffffff"
            }

            Repeater {
                model: 4
                Rectangle {
                    property int channel: index
                    x: 48
                    y: 132 + index * 92
                    width: 500
                    height: 72
                    color: root.accentEditorChannel === channel ?
                           "#323d4656" : "#8c111720"
                    border.width: root.accentEditorChannel === channel ? 2 : 1
                    border.color: root.accentEditorChannel === channel ?
                                  root.accentEditorColor() : "#30ffffff"
                    radius: 6

                    Text {
                        x: 22
                        anchors.verticalCenter: parent.verticalCenter
                        text: ["TARGET", "HUE", "SATURATION", "LIGHTNESS"][parent.channel]
                        color: "#aeb6c8"
                        font.family: global.fonts.sans
                        font.pixelSize: 17
                        font.weight: Font.Bold
                        font.letterSpacing: 1.2
                    }
                    Text {
                        anchors.right: parent.right
                        anchors.rightMargin: 22
                        anchors.verticalCenter: parent.verticalCenter
                        width: 300
                        horizontalAlignment: Text.AlignRight
                        text: {
                            var targets = root.accentTargets()
                            if (parent.channel === 0)
                                return targets.length ? root.accentTargetLabel(
                                        targets[root.accentEditorTargetIndex]) : "NO SYSTEMS"
                            if (parent.channel === 1)
                                return Math.round(root.accentEditorHue * 360) + "°"
                            if (parent.channel === 2)
                                return Math.round(root.accentEditorSaturation * 100) + "%"
                            return Math.round(root.accentEditorLightness * 100) + "%"
                        }
                        color: root.accentEditorColor()
                        font.family: global.fonts.condensed
                        font.pixelSize: 25
                        fontSizeMode: Text.Fit
                        minimumPixelSize: 14
                        font.weight: Font.Bold
                        elide: Text.ElideNone
                    }
                    MouseArea {
                        anchors.fill: parent
                        onClicked: root.accentEditorChannel = parent.channel
                    }
                }
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                y: 550
                text: "UP / DOWN  FIELD     LEFT / RIGHT  ADJUST     Y  RESET     A  SAVE     B  CLOSE"
                color: "#8f99aa"
                font.family: global.fonts.sans
                font.pixelSize: 14
                font.letterSpacing: 1
            }
        }
    }

    Rectangle {
        id: aboutOverlay
        z: 830
        anchors.fill: parent
        visible: root.aboutOpen
        color: "#e805080d"

        MouseArea {
            anchors.fill: parent
            onClicked: { root.aboutOpen = false; root.forceActiveFocus() }
        }

        Rectangle {
            anchors.centerIn: parent
            width: 980
            height: 650
            color: "#fa0d121a"
            border.width: 2
            border.color: root.accent
            radius: 10

            MouseArea { anchors.fill: parent }

            Text {
                x: 56; y: 42
                text: "LUCENT  " + root.lucentVersion
                color: "white"
                font.family: global.fonts.condensed
                font.pixelSize: 48
                font.weight: Font.Bold
                font.letterSpacing: 2
            }
            Text {
                x: 58; y: 116; width: parent.width - 116
                text: "A complete Android game library and a standalone theme, built on Pegasus Frontend."
                wrapMode: Text.WordWrap
                color: "#d8deea"
                font.family: global.fonts.sans
                font.pixelSize: 21
                lineHeight: 1.3
            }
            Text {
                x: 58; y: 214; width: parent.width - 116
                text: "PEGASUS FRONTEND\nCreated by Mátyás M. and contributors\nLicensed under GNU GPL version 3\nBase source: github.com/mmatyas/pegasus-frontend/tree/6b322063\n\nLUCENT APP\nModified Pegasus distribution licensed under GPLv3. Complete source and build scripts: github.com/wildonrio/pegasus-lucent\n\nLUCENT THEME\nAlso available independently under the MIT License. Other Pegasus themes remain supported."
                wrapMode: Text.WordWrap
                color: "#aeb7c8"
                font.family: global.fonts.sans
                font.pixelSize: 19
                lineHeight: 1.35
            }
            Text {
                x: 58; y: 522; width: parent.width - 116
                text: "Platform names and logos are identification marks belonging to their respective owners. Their appearance does not imply sponsorship or endorsement."
                wrapMode: Text.WordWrap
                color: "#8f99aa"
                font.family: global.fonts.sans
                font.pixelSize: 16
                lineHeight: 1.3
            }
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                y: 604
                text: "A / B  CLOSE"
                color: root.accent
                font.family: global.fonts.sans
                font.pixelSize: 15
                font.weight: Font.Bold
                font.letterSpacing: 1.2
            }
        }
    }

}
