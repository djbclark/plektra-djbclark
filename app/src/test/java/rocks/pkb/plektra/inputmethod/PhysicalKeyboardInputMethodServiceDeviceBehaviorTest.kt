package rocks.pkb.plektra.inputmethod

import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.AltModifierBinding
import rocks.pkb.plektra.HardwareKeyRemapPresets
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SymPagesConfig
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.core.ModifierStateController
import rocks.pkb.plektra.data.layout.LayoutMappingRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import java.lang.reflect.Field
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhysicalKeyboardInputMethodServiceDeviceBehaviorTest {

    private lateinit var service: PhysicalKeyboardInputMethodService
    private lateinit var serviceController: ServiceController<PhysicalKeyboardInputMethodService>
    private lateinit var recorder: RecordingInputConnection
    private lateinit var inputConnection: InputConnection
    private lateinit var editorInfo: EditorInfo

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        DeviceSpecific.clearTestOverrides()
        SettingsManager.setSymPagesConfig(context, SymPagesConfig())
        SettingsManager.resetSymMappings(context)
        SettingsManager.resetSymMappingsPage2(context)
        SettingsManager.setStaticVariationBarLayerStickyEnabled(context, true)
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)
        SettingsManager.setAutoCapitalizeRespectManualShiftOff(context, true)
        SettingsManager.setAutoCapitalizeRestrictedFields(context, false)
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "auto")
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.DeviceSym)
        SettingsManager.setAppEnterBehaviorEnabled(context, true)
        SettingsManager.setAppEnterBehaviorPreset(
            context,
            SettingsManager.ENTER_BEHAVIOR_PRESET_ENTER_SEND_SHIFT_NEWLINE
        )
        SettingsManager.setAppEnterBehaviorOverrides(context, emptyList())

        serviceController = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        service = serviceController.get()

        recorder = RecordingInputConnection()
        inputConnection = recorder.asProxy()
        editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            packageName = "rocks.pkb.plektra.test"
        }

        // Inject active editable-field context so onKeyDown/onKeyUp run the same path as on device.
        setField(service, "mInputConnection", inputConnection)
        setField(service, "mStartedInputConnection", inputConnection)
        setField(service, "mInputEditorInfo", editorInfo)
        setField(service, "isInputViewActive", true)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
    }

    @After
    fun tearDown() {
        try {
            if (::serviceController.isInitialized) serviceController.destroy()
        } finally {
            DeviceSpecific.clearTestOverrides()
        }
    }

    @Test
    fun mp01DedicatedEmojiKey_isIgnoredWhenMp01IsNotActive() {
        DeviceSpecific.setBuildFingerprintForTests(
            brand = "unihertz",
            manufacturer = "unihertz",
            model = "Titan 2",
            device = "titan2",
            product = "titan2"
        )
        setField(service, "physicalKeyboardProfileOverride", "auto")

        val handled = service.onKeyDown(
            666,
            keyEvent(KeyEvent.ACTION_DOWN, 666, 6_000L, 6_000L)
        )

        assertFalse(handled)
    }

    @Test
    fun mp01DedicatedEmojiKey_isHandledWhenManualMp01OverrideIsActive() {
        DeviceSpecific.setBuildFingerprintForTests(
            brand = "unihertz",
            manufacturer = "unihertz",
            model = "Titan 2",
            device = "titan2",
            product = "titan2"
        )
        setField(service, "physicalKeyboardProfileOverride", "mp01")

        val handled = service.onKeyDown(
            666,
            keyEvent(KeyEvent.ACTION_DOWN, 666, 6_100L, 6_100L)
        )

        assertTrue(handled)
    }

    @Test
    fun ctrlHoldShortcutRelease_doesNotLeaveCtrlActive() {
        val t0 = 1_000L

        service.onKeyDown(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, t0, t0)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_A,
                downTime = t0,
                eventTime = t0 + 120L,
                metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_A,
            keyEvent(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_A,
                downTime = t0,
                eventTime = t0 + 150L,
                metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_CTRL_LEFT,
                downTime = t0,
                eventTime = t0 + 420L
            )
        )

        val modifier = modifierController()
        assertFalse(modifier.ctrlPressed)
        assertFalse(modifier.ctrlPhysicallyPressed)
        assertFalse(modifier.ctrlOneShot)
        assertFalse(modifier.ctrlLatchActive)
    }

    /** The emoji picker's search open for typing, as the software keyboard's picker page has it. */
    private fun openEmojiPickerSearch(): rocks.pkb.plektra.inputmethod.ui.EmojiPickerView {
        val context = RuntimeEnvironment.getApplication()
        val sym: rocks.pkb.plektra.core.SymLayoutController = getField(service, "symLayoutController")
        if (sym.currentSymPage() != 4) sym.openEmojiPickerPage()
        val bars: CandidatesBarController = getField(service, "candidatesBarController")
        val picker = rocks.pkb.plektra.inputmethod.ui.EmojiPickerView(context)
        setField(getField<StatusBarController>(bars, "inputStatusBar"), "emojiPickerView", picker)
        picker.showSearch()
        assertTrue(bars.isEmojiPickerSearchInputActive())
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("emoji", "🐱"))
        return picker
    }

    private fun searchText(picker: rocks.pkb.plektra.inputmethod.ui.EmojiPickerView): String =
        getField<android.widget.EditText>(picker, "searchField").text.toString()

    private fun pressKey(keyCode: Int, start: Long, metaState: Int = 0) {
        service.onKeyDown(keyCode, keyEvent(KeyEvent.ACTION_DOWN, keyCode, start, start, metaState))
        service.onKeyUp(keyCode, keyEvent(KeyEvent.ACTION_UP, keyCode, start, start + 30L, metaState))
    }

    @Test
    fun hardwareCtrlVIntoTheEmojiSearchPastesAndLeavesCtrlOff() {
        val ctrlMeta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        for ((round, holdMs) in listOf(150L, 420L).withIndex()) {
            val picker = openEmojiPickerSearch()
            val t0 = 6_000L + round * 1_000L
            service.onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, t0, t0, ctrlMeta))
            pressKey(KeyEvent.KEYCODE_V, t0 + 60L, ctrlMeta)
            service.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, t0, t0 + holdMs))

            assertEquals("held ${holdMs}ms", "🐱", searchText(picker))
            val modifier = modifierController()
            assertFalse("held ${holdMs}ms", modifier.ctrlOneShot)
            assertFalse(modifier.ctrlLatchActive)
            assertFalse(modifier.ctrlPressed)
            assertFalse(modifier.ctrlPhysicallyPressed)
            // The next key types its letter into the search, without Ctrl
            pressKey(KeyEvent.KEYCODE_A, t0 + 600L)
            assertEquals("🐱a", searchText(picker))
            assertEquals(emptyList<String>(), recorder.committedTexts)
        }
    }

    @Test
    fun ctrlVIntoTheEmojiSearchUsesUpACtrlOneShotButKeepsALatchedCtrl() {
        val picker = openEmojiPickerSearch()
        val modifier = modifierController()
        modifier.ctrlOneShot = true
        pressKey(KeyEvent.KEYCODE_V, 7_000L)
        assertEquals("🐱", searchText(picker))
        assertFalse(modifier.ctrlOneShot)
        pressKey(KeyEvent.KEYCODE_A, 7_100L)
        assertEquals("🐱a", searchText(picker))

        // A latched Ctrl stays latched: the user wants several shortcuts
        modifier.ctrlLatchActive = true
        pressKey(KeyEvent.KEYCODE_V, 7_200L)
        assertEquals("🐱a🐱", searchText(picker))
        assertTrue(modifier.ctrlLatchActive)
    }

    @Test
    fun altDoubleTapLatch_thenSingleTapFullyDeactivates() {
        val t0 = 2_000L

        tapAlt(t0)          // one-shot
        tapAlt(t0 + 120L)   // latch (+ visual altModifierLayerLatched on quick double tap)
        tapAlt(t0 + 240L)   // user expects off; device reports still active

        val modifier = modifierController()
        val altModifierLayerLatched = getField<Boolean>(service, "altModifierLayerLatched")

        assertFalse(modifier.altOneShot)
        assertFalse(modifier.altLatchActive)
        assertFalse(altModifierLayerLatched)
    }

    @Test
    fun shiftDoubleTapLatch_thenSingleTapFullyDeactivates() {
        val t0 = 2_500L

        tapShift(t0)          // one-shot
        tapShift(t0 + 120L)   // latch (+ visual shiftLayerLatched on quick double tap)
        tapShift(t0 + 240L)   // user expects off

        val modifier = modifierController()
        val shiftLayerLatched = getField<Boolean>(service, "shiftLayerLatched")

        assertFalse(modifier.shiftOneShot)
        assertFalse(modifier.capsLockEnabled)
        assertFalse(shiftLayerLatched)
    }

    @Test
    fun clicksPower_bouncedShiftTapDoesNotEnableCapsLock() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "clicks_power")
        setField(service, "physicalKeyboardProfileOverride", "clicks_power")
        val t0 = 2_600L

        tapShift(t0)
        tapShift(t0 + 60L)

        val modifier = modifierController()
        assertTrue(modifier.shiftOneShot)
        assertFalse(modifier.capsLockEnabled)
        assertFalse(getField<Boolean>(service, "shiftLayerLatched"))
    }

    @Test
    fun clicksPower_intentionalShiftDoubleTapStillEnablesCapsLock() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "clicks_power")
        setField(service, "physicalKeyboardProfileOverride", "clicks_power")
        val t0 = 2_700L

        tapShift(t0)
        tapShift(t0 + 120L)

        val modifier = modifierController()
        assertFalse(modifier.shiftOneShot)
        assertTrue(modifier.capsLockEnabled)
        assertTrue(getField<Boolean>(service, "shiftLayerLatched"))
    }

    @Test
    fun clicksPower_sameFrameShiftMacroDoesNotToggleLogicalShift() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "clicks_power")
        setField(service, "physicalKeyboardProfileOverride", "clicks_power")

        repeat(4) { index ->
            typeClicksSyntheticAtMacro(3_000L + index * 400L)
            val modifier = modifierController()
            assertFalse(modifier.shiftOneShot)
            assertFalse(modifier.capsLockEnabled)
            assertFalse(modifier.shiftPressed)
            assertFalse(modifier.shiftPhysicallyPressed)
        }
    }

    @Test
    fun shiftEnter_consumesManualShift_whenAutoCapitalizeAtLineStartIsDisabled() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setField(service, "mInputEditorInfo", editorInfo)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
        val t0 = 2_700L

        service.onKeyDown(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, t0, t0)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                downTime = t0,
                eventTime = t0 + 80L,
                metaState = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            )
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, t0, t0 + 120L)
        )

        val modifier = modifierController()
        assertFalse(modifier.shiftPressed)
        assertFalse(modifier.shiftPhysicallyPressed)
        assertFalse(modifier.shiftOneShot)
        assertFalse(modifier.capsLockEnabled)

        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 200L, t0 + 200L)
        )
        assertTrue("commits=${recorder.committedTexts}", recorder.committedTexts.contains("a"))
        assertFalse("commits=${recorder.committedTexts}", recorder.committedTexts.contains("A"))
    }

    @Test
    fun appEnter_knownFacebookMessengerWithoutOverride_usesEditorAction() {
        focusNewField(
            newRecorder = RecordingInputConnection(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            packageName = "com.facebook.orca"
        )
        recorder.performEditorActionResult = true

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_750L, 2_750L)
        )

        assertTrue(handled)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), recorder.editorActions)
        assertTrue(recorder.sentKeyEvents.isEmpty())
        assertFalse(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun appEnter_beeperWithoutOverride_usesEditorAction() {
        focusNewField(RecordingInputConnection(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, "com.beeper.android")
        service.onKeyDown(KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_750L, 2_750L))
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), recorder.editorActions)
        assertFalse(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun appEnter_beeperShiftEnter_insertsNewlineInsteadOfSending() {
        focusNewField(RecordingInputConnection(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, "com.beeper.android")
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 2_740L, 2_740L))
        service.onKeyDown(KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_750L, 2_750L, KeyEvent.META_SHIFT_ON))
        assertTrue(recorder.editorActions.isEmpty())
        assertTrue(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun appEnter_beeperNativeOverride_preservesNativeEnterAfterUpdate() {
        SettingsManager.setAppEnterBehaviorOverride(RuntimeEnvironment.getApplication(),
            "com.beeper.android", SettingsManager.ENTER_BEHAVIOR_APP_DEFAULT)
        focusNewField(RecordingInputConnection(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, "com.beeper.android")
        service.onKeyDown(KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_750L, 2_750L))
        assertTrue(recorder.editorActions.isEmpty())
    }

    @Test
    fun appEnter_beeperExplicitNewline_winsOverSendPreset() {
        SettingsManager.setAppEnterBehaviorOverride(RuntimeEnvironment.getApplication(),
            "com.beeper.android", SettingsManager.ENTER_BEHAVIOR_ENTER_NEWLINE)
        focusNewField(RecordingInputConnection(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, "com.beeper.android")
        service.onKeyDown(KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_750L, 2_750L))
        assertTrue(recorder.editorActions.isEmpty())
        assertTrue(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun appEnter_beeperDisabled_doesNotSend() {
        SettingsManager.setAppEnterBehaviorEnabled(RuntimeEnvironment.getApplication(), false)
        focusNewField(RecordingInputConnection(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, "com.beeper.android")
        service.onKeyDown(KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_750L, 2_750L))
        assertTrue(recorder.editorActions.isEmpty())
    }

    @Test
    fun appEnter_facebookMessengerManualEditorStrategy_usesEditorAction() {
        configureAppEnterOverride(
            packageName = "com.facebook.orca",
            strategy = SettingsManager.ENTER_SEND_STRATEGY_EDITOR_ACTION
        )
        recorder.performEditorActionResult = true

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_755L, 2_755L)
        )

        assertTrue(handled)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), recorder.editorActions)
        assertTrue(recorder.sentKeyEvents.isEmpty())
        assertFalse(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun appEnter_manualPlainEnter_sendsExactKeyPair_forUnlistedApp() {
        configureAppEnterOverride(
            packageName = "com.example.unlisted.chat",
            strategy = SettingsManager.ENTER_SEND_STRATEGY_PLAIN_ENTER
        )
        recorder.sendKeyEventResult = false

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_760L, 2_760L)
        )

        assertTrue(handled)
        assertEquals(2, recorder.sentKeyEvents.size)
        assertTrue(recorder.sentKeyEvents.all { it.keyCode == KeyEvent.KEYCODE_ENTER })
        assertFalse(recorder.sentKeyEvents.any { it.isCtrlPressed })
        assertTrue(recorder.editorActions.isEmpty())
        assertFalse(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun appEnter_manualCtrlEnter_sendsExactModifiedKeyPair_forUnlistedApp() {
        configureAppEnterOverride(
            packageName = "com.example.unlisted.chat",
            strategy = SettingsManager.ENTER_SEND_STRATEGY_CTRL_ENTER
        )

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_770L, 2_770L)
        )

        assertTrue(handled)
        assertEquals(2, recorder.sentKeyEvents.size)
        assertTrue(recorder.sentKeyEvents.all { it.keyCode == KeyEvent.KEYCODE_ENTER })
        assertTrue(recorder.sentKeyEvents.all { it.isCtrlPressed })
        assertTrue(recorder.editorActions.isEmpty())
        assertFalse(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun appEnter_manualEditorAction_doesNotFallBackToNewline_whenRejected() {
        configureAppEnterOverride(
            packageName = "com.example.unlisted.chat",
            strategy = SettingsManager.ENTER_SEND_STRATEGY_EDITOR_ACTION
        )
        recorder.performEditorActionResult = false

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 2_780L, 2_780L)
        )

        assertTrue(handled)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), recorder.editorActions)
        assertTrue(recorder.sentKeyEvents.isEmpty())
        assertFalse(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun capWords_allAutoCapDisabled_clicksPowerAltPThenAStaysLowercase() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "clicks_power")
        setField(service, "physicalKeyboardProfileOverride", "clicks_power")
        getField<AlternateCharacterManager>(service, "alternateCharacterManager")
            .reloadModifierAndDeviceSymMappings()
        focusNewField(
            newRecorder = RecordingInputConnection(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        )

        assertFalse(modifierController().shiftOneShot)

        val t0 = 2_800L
        tapAlt(t0)
        service.onKeyDown(
            KeyEvent.KEYCODE_P,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P, t0 + 40L, t0 + 40L)
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_P,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_P, t0 + 40L, t0 + 60L)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 80L, t0 + 80L)
        )

        assertEquals("@a", recorder.textBeforeCursor)
        assertFalse(
            AutoCapitalizeHelper.shouldCapitalizeAfterBoundary(
                context = context,
                state = InputContextState.fromEditorInfo(editorInfo),
                inputConnection = inputConnection,
                keyCode = KeyEvent.KEYCODE_SPACE
            )
        )
    }

    @Test
    fun autoCap_manualShiftOff_doesNotLeakIntoAnotherEmptyField() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)

        service.onStartInput(editorInfo, false)
        assertTrue(modifierController().shiftOneShot)

        tapShift(2_900L)
        assertFalse(modifierController().shiftOneShot)

        focusNewField(RecordingInputConnection())

        assertTrue(modifierController().shiftOneShot)
    }

    @Test
    fun uyghurLayout_neverAutomaticallySelectsExtraLettersButManualShiftWorks() {
        SettingsManager.setKeyboardLayoutAutoByLocale(service, false)
        SettingsManager.setKeyboardLayout(service, "uyghur")
        LayoutMappingRepository.loadLayout(service.assets, "uyghur", service)
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS

        service.onStartInput(editorInfo, false)
        assertFalse(modifierController().shiftOneShot)
        assertFalse(LayoutMappingRepository.supportsAutoCapitalization)
        tapShift(3_000L)
        assertTrue(modifierController().shiftOneShot)

        editorInfo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        service.onStartInput(editorInfo, false)
        assertFalse(modifierController().capsLockEnabled)

        LayoutMappingRepository.loadLayout(service.assets, "qwerty", service)
        assertTrue(LayoutMappingRepository.supportsAutoCapitalization)
    }

    @Test
    fun switchingToUyghur_clearsPendingCapitalizationFromEnglish() {
        service.onStartInput(editorInfo, false)
        assertTrue(modifierController().shiftOneShot)
        switchSubtype("ug:uyghur")
        assertFalse(modifierController().shiftOneShot)
        assertEquals("د", LayoutMappingRepository.getLowercase(KeyEvent.KEYCODE_D))
    }

    @Test
    fun switchingToUyghur_suspendsFieldCapsLockAndRestoresItOnLatin() {
        SettingsManager.setKeyboardLayoutAutoByLocale(service, false)
        SettingsManager.setKeyboardLayout(service, "qwerty")
        SettingsManager.setAutoCorrectEnabled(service, false)
        LayoutMappingRepository.loadLayout(service.assets, "qwerty", service)
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        service.onStartInput(editorInfo, false)
        assertTrue(modifierController().capsLockEnabled)

        switchSubtype("ug:uyghur")
        assertFalse(modifierController().capsLockEnabled)
        service.onKeyDown(KeyEvent.KEYCODE_D, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D, 10_000L, 10_000L))
        service.onKeyUp(KeyEvent.KEYCODE_D, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_D, 10_000L, 10_030L))
        assertEquals("د", recorder.committedTexts.joinToString(""))

        switchSubtype("en_US:qwertz")
        assertTrue(modifierController().capsLockEnabled)
    }

    @Test
    fun switchingToUyghur_keepsCapsLockTheUserTurnedOn() {
        SettingsManager.setKeyboardLayoutAutoByLocale(service, false)
        SettingsManager.setKeyboardLayout(service, "qwerty")
        LayoutMappingRepository.loadLayout(service.assets, "qwerty", service)
        service.onStartInput(editorInfo, false)
        modifierController().capsLockEnabled = true

        switchSubtype("ug:uyghur")
        assertTrue(modifierController().capsLockEnabled)
    }

    @Test
    fun fieldRestartOnUyghur_keepsFieldCapsLockSuspendedForLatin() {
        startCapCharactersFieldOnQwerty()
        switchSubtype("ug:uyghur")
        service.onStartInput(editorInfo, true)
        service.onStartInputView(editorInfo, true)
        assertFalse(modifierController().capsLockEnabled)

        switchSubtype("en_US:qwertz")
        assertTrue(modifierController().capsLockAutomatic)
    }

    @Test
    fun manualCapsLock_staysUserOwnedAcrossFieldRestartAndUyghurSwitch() {
        SettingsManager.setStaticVariationBarLayerStickyEnabled(service, false)
        startCapCharactersFieldOnQwerty()
        assertTrue(modifierController().capsLockAutomatic)
        tapShift(10_000L)
        tapShift(10_050L)
        assertTrue(modifierController().capsLockEnabled)
        assertFalse(modifierController().capsLockAutomatic)

        service.onStartInput(editorInfo, true)
        service.onStartInputView(editorInfo, true)
        assertFalse(modifierController().capsLockAutomatic)

        switchSubtype("ug:uyghur")
        assertTrue(modifierController().capsLockEnabled)
        switchSubtype("en_US:qwertz")
        assertTrue(modifierController().capsLockEnabled)
        assertFalse(modifierController().capsLockAutomatic)
    }

    @Test
    fun shiftHeldAcrossSwitchToUyghur_doesNotRestoreFieldCapsLock() {
        SettingsManager.setAutoCorrectEnabled(service, false)
        startCapCharactersFieldOnQwerty()
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_000L))
        switchSubtype("ug:uyghur")
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_400L))
        assertFalse(modifierController().capsLockEnabled)

        service.onKeyDown(KeyEvent.KEYCODE_D, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D, 11_000L, 11_000L))
        service.onKeyUp(KeyEvent.KEYCODE_D, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_D, 11_000L, 11_030L))
        assertEquals("د", recorder.committedTexts.joinToString(""))

        switchSubtype("en_US:qwertz")
        assertTrue(modifierController().capsLockAutomatic)
    }

    @Test
    fun shiftHeldAcrossSwitchToLatin_restoresSuspendedFieldCapsLock() {
        startCapCharactersFieldOnQwerty()
        switchSubtype("ug:uyghur")
        assertFalse(modifierController().capsLockEnabled)

        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_000L))
        switchSubtype("en_US:qwertz")
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_400L))

        assertTrue(modifierController().capsLockAutomatic)
    }

    @Test
    fun fieldRestartDroppingCapsDuringShiftHoldOnUyghur_doesNotBringFieldCapsLockBackOnLatin() {
        startCapCharactersFieldOnQwerty()
        switchSubtype("ug:uyghur")
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_000L))
        restartFieldWithoutCapCharacters()
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_400L))

        switchSubtype("en_US:qwertz")
        assertFalse(modifierController().capsLockAutomatic)
        assertFalse(modifierController().capsLockEnabled)
    }

    @Test
    fun fieldRestartDroppingCapsDuringShiftHoldOnLatin_doesNotRestoreFieldCapsLock() {
        startCapCharactersFieldOnQwerty()
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_000L))
        restartFieldWithoutCapCharacters()
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_400L))
        assertFalse(modifierController().capsLockEnabled)

        switchSubtype("ug:uyghur")
        switchSubtype("en_US:qwertz")
        assertFalse(modifierController().capsLockEnabled)
    }

    @Test
    fun fieldRestartDroppingCapsDuringShiftHeldAcrossSwitchToLatin_doesNotRestoreFieldCapsLock() {
        startCapCharactersFieldOnQwerty()
        switchSubtype("ug:uyghur")
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_000L))
        restartFieldWithoutCapCharacters()
        switchSubtype("en_US:qwertz")
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_400L))
        assertFalse(modifierController().capsLockEnabled)
    }

    @Test
    fun fieldRestartDroppingCapsDuringShiftHeldAcrossSwitchToUyghur_doesNotRestoreFieldCapsLockOnLatin() {
        startCapCharactersFieldOnQwerty()
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_000L))
        restartFieldWithoutCapCharacters()
        switchSubtype("ug:uyghur")
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 10_000L, 10_400L))

        switchSubtype("en_US:qwertz")
        assertFalse(modifierController().capsLockEnabled)
    }

    @Test
    fun fieldRestartDroppingCapsOnUyghurWithoutHold_keepsRequestCleared() {
        startCapCharactersFieldOnQwerty()
        switchSubtype("ug:uyghur")
        restartFieldWithoutCapCharacters()
        switchSubtype("en_US:qwertz")
        assertFalse(modifierController().capsLockEnabled)
    }

    @Test
    fun fieldRestartDroppingCapsOnLatinWithoutHold_turnsFieldCapsLockOff() {
        startCapCharactersFieldOnQwerty()
        restartFieldWithoutCapCharacters()
        assertFalse(modifierController().capsLockEnabled)
    }

    private fun restartFieldWithoutCapCharacters() {
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT
        service.onStartInput(editorInfo, true)
        service.onStartInputView(editorInfo, true)
    }

    private fun startCapCharactersFieldOnQwerty() {
        SettingsManager.setKeyboardLayoutAutoByLocale(service, false)
        SettingsManager.setKeyboardLayout(service, "qwerty")
        LayoutMappingRepository.loadLayout(service.assets, "qwerty", service)
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        service.onStartInput(editorInfo, false)
        assertTrue(modifierController().capsLockAutomatic)
    }

    @Test
    fun midWordQuoteToApostrophe_keepsTheLayoutOutputOfTheNextKey() {
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        assertEquals("ب'د", typeAfterMidWordQuote("uyghur", "ب", KeyEvent.KEYCODE_D))
        assertEquals("ب'لا", typeAfterMidWordQuote("uyghur", "ب", KeyEvent.KEYCODE_L, shift))
        assertEquals("ب'ئ", typeAfterMidWordQuote("uyghur", "ب", KeyEvent.KEYCODE_SLASH))
        assertEquals("а'в", typeAfterMidWordQuote("russian_standard", "а", KeyEvent.KEYCODE_D))
        assertEquals("α'δ", typeAfterMidWordQuote("greek", "α", KeyEvent.KEYCODE_D))
        assertEquals("a'd", typeAfterMidWordQuote("qwerty", "a", KeyEvent.KEYCODE_D))
        assertEquals("a'D", typeAfterMidWordQuote("qwerty", "a", KeyEvent.KEYCODE_D, shift))
    }

    @Test
    fun greekPunctuationOnLetterKey_isCommittedOnceAsPunctuation() {
        useLayout("greek")
        recorder.textBeforeCursor = "αβ"
        val time = 10_000L
        service.onKeyDown(KeyEvent.KEYCODE_Q, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q, time, time))
        service.onKeyUp(KeyEvent.KEYCODE_Q, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Q, time, time + 30L))
        assertEquals("αβ;", recorder.textBeforeCursor)
    }

    @Test
    fun softShiftOnUyghur_isConsumedOnceByTheMultiCharacterTextOfTheNextSoftKey() {
        useLayout("uyghur")
        val keyboard = SoftwareKeyboardHarness()

        keyboard.tap("⇧")
        assertTrue(modifierController().shiftOneShot)
        keyboard.tap("l")
        assertFalse(modifierController().shiftOneShot)
        keyboard.tap("k")

        assertEquals(listOf("لا", "ك"), recorder.committedTexts)
    }

    @Test
    fun softShiftOnLatin_isConsumedOnceByTheNextSoftKey() {
        useLayout("qwerty")
        val keyboard = SoftwareKeyboardHarness()

        keyboard.tap("⇧")
        keyboard.tap("a")
        keyboard.tap("b")

        assertEquals(listOf("A", "b"), recorder.committedTexts)
    }

    /**
     * The software keyboard view wired to the service's own callbacks: taps go through
     * AospKeyboardView touch handling, and every tap re-renders it from the service's
     * modifier state, as the status bar does on device.
     */
    private inner class SoftwareKeyboardHarness {
        private val context = RuntimeEnvironment.getApplication()
        private val statusBar: StatusBarController =
            getField(getField<Any>(service, "candidatesBarController"), "inputStatusBar")
        private val view = rocks.pkb.plektra.inputmethod.aospkeyboard.AospKeyboardView(context)
        private val parent = android.widget.FrameLayout(context).apply {
            addView(view, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        private var time = 0L

        init {
            render()
        }

        fun tap(label: String) {
            val key = getField<List<Any>>(view, "keys").first { key ->
                getField<String>(getField<Any>(key, "spec"), "label") == label
            }
            val rect = getField<android.graphics.RectF>(key, "hitRect")
            time += 100L
            view.dispatchTouchEvent(android.view.MotionEvent.obtain(0L, time, android.view.MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), 0))
            view.dispatchTouchEvent(android.view.MotionEvent.obtain(0L, time + 20L, android.view.MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 0))
            render()
        }

        private fun render() {
            val modifiers = modifierController().snapshot()
            val snapshot = StatusBarController.StatusSnapshot(
                capsLockEnabled = modifiers.capsLockEnabled,
                shiftPhysicallyPressed = modifiers.shiftPhysicallyPressed,
                shiftOneShot = modifiers.shiftOneShot,
                ctrlLatchActive = modifiers.ctrlLatchActive,
                ctrlPhysicallyPressed = modifiers.ctrlPhysicallyPressed,
                ctrlOneShot = modifiers.ctrlOneShot,
                ctrlLatchFromNavMode = modifiers.ctrlLatchFromNavMode,
                altLatchActive = modifiers.altLatchActive,
                altPhysicallyPressed = modifiers.altPhysicallyPressed,
                altOneShot = modifiers.altOneShot,
                symPage = 0,
                activeKeyboardLayoutName = SettingsManager.getKeyboardLayout(context)
            )
            StatusBarController::class.java.getDeclaredMethod(
                "configureSoftwareKeyboard",
                view.javaClass,
                snapshot.javaClass,
                InputConnection::class.java,
                Map::class.java
            ).apply { isAccessible = true }.invoke(statusBar, view, snapshot, inputConnection, null)
            val width = android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY)
            val height = android.view.View.MeasureSpec.makeMeasureSpec(240, android.view.View.MeasureSpec.EXACTLY)
            parent.measure(width, height)
            parent.layout(0, 0, 1000, 240)
        }
    }

    private fun useLayout(layout: String) {
        SettingsManager.setKeyboardLayoutAutoByLocale(service, false)
        SettingsManager.setKeyboardLayout(service, layout)
        SettingsManager.setAutoCorrectEnabled(service, false)
        SettingsManager.setMidWordQuoteToApostrophe(service, true)
        LayoutMappingRepository.loadLayout(service.assets, layout, service)
        service.onStartInput(editorInfo, false)
        // The tests type mid-sentence; drop the auto-cap one-shot of the empty field.
        modifierController().consumeShiftOneShot()
        recorder.committedTexts.clear()
    }

    private fun typeAfterMidWordQuote(layout: String, letter: String, keyCode: Int, meta: Int = 0): String {
        useLayout(layout)
        recorder.textBeforeCursor = "$letter\""
        val time = 10_000L
        service.onKeyDown(keyCode, keyEvent(KeyEvent.ACTION_DOWN, keyCode, time, time, meta))
        service.onKeyUp(keyCode, keyEvent(KeyEvent.ACTION_UP, keyCode, time, time + 30L, meta))
        return recorder.textBeforeCursor
    }

    private fun switchSubtype(prefValue: String) {
        val subtype = rocks.pkb.plektra.inputmethod.subtype.AdditionalSubtypeUtils.createAdditionalSubtypesArray(
            prefValue, service.assets, service
        ).single()
        PhysicalKeyboardInputMethodService::class.java.getDeclaredMethod(
            "onCurrentInputMethodSubtypeChanged", android.view.inputmethod.InputMethodSubtype::class.java
        ).apply { isAccessible = true }.invoke(service, subtype)
    }

    @Test
    fun uyghurOnQ25_routesAllLettersAndPunctuationThroughRealImePath() {
        DeviceSpecific.setBuildFingerprintForTests("zinwa", "zinwa", "Q25", "Q25", "q25")
        SettingsManager.setPhysicalKeyboardProfileOverride(service, "Q25")
        setField(service, "physicalKeyboardProfileOverride", "Q25")
        SettingsManager.setKeyboardLayoutAutoByLocale(service, false)
        SettingsManager.setKeyboardLayout(service, "uyghur")
        SettingsManager.setAutoCorrectEnabled(service, false)
        SettingsManager.setLongPressModifier(service, "alt")
        LayoutMappingRepository.loadLayout(service.assets, "uyghur", service)
        service.onStartInput(editorInfo, false)

        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        var time = 10_000L
        fun press(key: Int, meta: Int = 0) {
            service.onKeyDown(key, keyEvent(KeyEvent.ACTION_DOWN, key, time, time, meta))
            service.onKeyUp(key, keyEvent(KeyEvent.ACTION_UP, key, time, time + 30L, meta))
            time += 1_000L
        }
        "QWERTYUIOPASDFGHJKLZXCVBNM".forEach { press(KeyEvent.KEYCODE_A + (it - 'A')) }
        "DFGHJKLMNVX".forEach { press(KeyEvent.KEYCODE_A + (it - 'A'), shift) }
        listOf(KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_SEMICOLON).forEach { press(it) }
        "NVJRHBIM".forEach { press(KeyEvent.KEYCODE_A + (it - 'A'), alt) }

        assertEquals(
            "چۋېرتيۇڭوپھسداەىقكلزشغۈبنمژفگخجۆلائنۈشئ،؛،؟؛3:!-.",
            recorder.committedTexts.joinToString("")
        )
    }

    @Test
    fun autoCap_manualShiftOff_survivesRestartOfCurrentField() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)

        service.onStartInput(editorInfo, false)
        assertTrue(modifierController().shiftOneShot)

        tapShift(3_000L)
        assertFalse(modifierController().shiftOneShot)

        service.onStartInput(editorInfo, true)

        assertFalse(modifierController().shiftOneShot)
    }

    @Test
    fun autoCap_manualShiftOff_doesNotSurviveRestart_whenRespectIsDisabled() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)
        SettingsManager.setAutoCapitalizeRespectManualShiftOff(context, false)

        service.onStartInput(editorInfo, false)
        assertTrue(modifierController().shiftOneShot)

        tapShift(3_100L)
        assertFalse(modifierController().shiftOneShot)

        service.onStartInput(editorInfo, true)

        assertTrue(modifierController().shiftOneShot)
    }

    @Test
    fun literalEditorOptOutSurvivesRestrictedCapitalizationAndAllowsManualShift() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeRestrictedFields(context, true)
        editorInfo.privateImeOptions = InputContextState.LITERAL_ENTRY_IME_OPTION
        service.onStartInput(editorInfo, false)
        assertFalse("Literal spelling must not inherit automatic Shift", modifierController().shiftOneShot)

        tapShift(20_000L)
        assertTrue("Deliberate capitalization remains available", modifierController().shiftOneShot)
        tapShift(21_000L)
        assertFalse(modifierController().shiftOneShot)

        focusNewField(RecordingInputConnection(), inputType = InputType.TYPE_CLASS_TEXT)
        assertTrue("Ordinary editors retain the user's capitalization preference", modifierController().shiftOneShot)
    }

    @Test
    fun autoCap_restrictedFieldsSetting_startsUriFieldWithShift() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeRestrictedFields(context, true)

        focusNewField(
            newRecorder = RecordingInputConnection(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )

        assertTrue(modifierController().shiftOneShot)
    }

    @Test
    fun autoCap_restrictedFieldsSetting_neverStartsPasswordWithShift() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeRestrictedFields(context, true)

        focusNewField(
            newRecorder = RecordingInputConnection(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        assertFalse(modifierController().shiftOneShot)
    }

    @Test
    fun autoCap_newFieldAfterNewline_startsWithShift() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, true)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, true)
        val nextField = RecordingInputConnection().apply {
            textBeforeCursor = "Previous line\n"
        }

        focusNewField(nextField)

        assertTrue(modifierController().shiftOneShot)
    }

    @Test
    fun deviceSanity_ctrlLatch_canBeDisabledByAnotherCtrlTap() {
        // Latch timing uses KeyEvent.eventTime (not wall-clock), so these gaps stay inside the
        // 500ms double-tap window regardless of how slowly the test runner schedules the calls.
        val t0 = 3_000L
        tapCtrl(t0)
        tapCtrl(t0 + 120L) // latch
        assertTrue(modifierController().ctrlLatchActive)
        tapCtrl(t0 + 260L) // toggle off

        val modifier = modifierController()
        assertFalse(modifier.ctrlOneShot)
        assertFalse(modifier.ctrlLatchActive)
    }

    @Test
    fun deviceSanity_ctrlStickyThenA_triggersShortcutPath() {
        val t0 = 3_600L
        tapCtrl(t0) // one-shot

        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 60L, t0 + 60L)
        )

        assertTrue(
            recorder.contextMenuActions.contains(android.R.id.selectAll) ||
                recorder.sentKeyEvents.isNotEmpty()
        )
    }

    @Test
    fun deviceSanity_altHoldThenA_doesNotStickAlt() {
        val t0 = 3_800L
        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, t0, t0)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_A,
                downTime = t0,
                eventTime = t0 + 80L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_A,
            keyEvent(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_A,
                downTime = t0,
                eventTime = t0 + 110L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, t0, t0 + 300L)
        )

        val modifier = modifierController()
        assertFalse(modifier.altPressed)
        assertFalse(modifier.altPhysicallyPressed)
        assertFalse(modifier.altLatchActive)
    }

    @Test
    fun altShiftLayoutSwitch_consumesShortcutAndClearsModifiers_whenEnabled() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAltShiftLayoutSwitchEnabled(context, true)
        val t0 = 3_900L

        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, t0, t0)
        )
        assertTrue(modifierController().altPressed)

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_SHIFT_LEFT,
                downTime = t0,
                eventTime = t0 + 80L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
        )

        val modifier = modifierController()
        assertTrue(handled)
        assertFalse(modifier.altPressed)
        assertFalse(modifier.altPhysicallyPressed)
        assertFalse(modifier.altOneShot)
        assertFalse(modifier.altLatchActive)
        assertFalse(modifier.shiftPressed)
        assertFalse(modifier.shiftOneShot)
        assertFalse(modifier.capsLockEnabled)
    }

    @Test
    fun altShiftLayoutSwitch_ignoresAltOneShot_whenShiftPressedLater() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAltShiftLayoutSwitchEnabled(context, true)
        val t0 = 3_950L

        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, t0, t0)
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, t0, t0 + 40L)
        )
        assertTrue(modifierController().altOneShot)

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, t0 + 120L, t0 + 120L)
        )

        val modifier = modifierController()
        assertFalse(handled)
        assertTrue(modifier.altOneShot)
        assertTrue(modifier.shiftOneShot)
        assertFalse(modifier.altPhysicallyPressed)
    }

    @Test
    fun altEnterLayoutSwitch_consumesShortcutAndClearsModifiers_whenEnabled() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAltEnterLayoutSwitchEnabled(context, true)
        val t0 = 3_900L

        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, t0, t0)
        )
        assertTrue(modifierController().altPressed)

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                downTime = t0,
                eventTime = t0 + 80L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
        )

        val modifier = modifierController()
        assertTrue(handled)
        assertFalse(modifier.altPressed)
        assertFalse(modifier.altPhysicallyPressed)
        assertFalse(modifier.altOneShot)
        assertFalse(modifier.altLatchActive)

        val keyUpHandled = service.onKeyUp(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_ENTER,
                downTime = t0,
                eventTime = t0 + 100L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
        )
        assertTrue(keyUpHandled)
    }

    @Test
    fun altEnterLayoutSwitch_doesNotConsumeWhenDisabled() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAltEnterLayoutSwitchEnabled(context, false)
        setField(service, "clearAltOnSpaceEnabled", false)
        val t0 = 3_920L

        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, t0, t0)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                downTime = t0,
                eventTime = t0 + 80L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
        )

        assertTrue(modifierController().altPhysicallyPressed)
    }

    @Test
    fun altEnterLayoutSwitch_consumesKeyRepeatsUntilKeyUp() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAltEnterLayoutSwitchEnabled(context, true)
        val t0 = 3_940L

        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, t0, t0)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                downTime = t0,
                eventTime = t0 + 80L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
        )

        val repeatedHandled = service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                downTime = t0,
                eventTime = t0 + 160L,
                metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON,
                repeatCount = 1
            )
        )

        assertTrue(repeatedHandled)
        assertFalse(recorder.committedTexts.contains("\n"))
        assertTrue(recorder.sentKeyEvents.isEmpty())
    }

    @Test
    fun altEnterLayoutSwitch_doesNotTriggerWhenEnterIsPressedFirst() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAltEnterLayoutSwitchEnabled(context, true)
        val t0 = 3_960L

        service.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, t0, t0)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ALT_LEFT,
                downTime = t0 + 80L,
                eventTime = t0 + 80L
            )
        )

        assertTrue(modifierController().altPressed)
        assertTrue(modifierController().altPhysicallyPressed)
    }

    @Test
    fun deviceSanity_symACyclesDeviceThenEmojiThenSymbols_exactMappings() {
        val t0 = 4_000L

        tapSym(t0)
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 20L, t0 + 20L)
        )
        assertTrue("commits=${recorder.committedTexts}", recorder.committedTexts.contains("@"))

        tapSym(t0 + 200L) // re-open SYM (Device SYM)
        tapSym(t0 + 260L) // next page -> emoji
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 300L, t0 + 300L)
        )
        assertTrue("commits=${recorder.committedTexts}", recorder.committedTexts.contains("😢"))

        tapSym(t0 + 400L) // re-open SYM (Device SYM)
        tapSym(t0 + 460L) // next page -> emoji
        tapSym(t0 + 520L) // next page -> symbols
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 560L, t0 + 560L)
        )
        assertTrue("commits=${recorder.committedTexts}", recorder.committedTexts.contains("="))
    }

    /** The emoji page's first People & Body page, opened as the service has it (no recents). */
    private fun openPeopleEmojiPage(): rocks.pkb.plektra.core.SymLayoutController {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("recent_emojis_prefs", 0).edit().clear().commit()
        rocks.pkb.plektra.data.emoji.TestEmojiFont.install(context)
        val sym: rocks.pkb.plektra.core.SymLayoutController = getField(service, "symLayoutController")
        sym.openEmojiPage()
        sym.turnPage(1, section = true)
        sym.turnPage(1, section = true)
        assertEquals("👋", sym.currentSymMappings()!![KeyEvent.KEYCODE_W])
        return sym
    }

    private fun rollOverWAndE(t0: Long) {
        service.onKeyDown(KeyEvent.KEYCODE_W, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, t0, t0))
        service.onKeyDown(KeyEvent.KEYCODE_E, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E, t0 + 30L, t0 + 30L))
        service.onKeyUp(KeyEvent.KEYCODE_W, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_W, t0, t0 + 60L))
        service.onKeyUp(KeyEvent.KEYCODE_E, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_E, t0 + 30L, t0 + 90L))
    }

    @Test
    fun emojiPageRolloverWithAutoCloseTypesTheSecondKeyAsALetterOnce() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        SettingsManager.setSymAutoClose(context, true)
        val sym = openPeopleEmojiPage()

        rollOverWAndE(5_000L)

        assertEquals(listOf("👋", "e"), recorder.committedTexts)
        assertFalse(sym.isSymActive())
    }

    @Test
    fun emojiPageRolloverWithoutAutoCloseTypesBothEmojiOnce() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSymAutoClose(context, false)
        val sym = openPeopleEmojiPage()
        val keys = sym.currentSymMappings()!!

        rollOverWAndE(5_200L)

        assertEquals(listOf(keys[KeyEvent.KEYCODE_W], keys[KeyEvent.KEYCODE_E]), recorder.committedTexts)
        assertTrue(sym.isSymActive())
    }

    @Test
    fun heldSymTypesTheOwnMappingOfQAndPThatThePagesTurnWith() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSymAutoClose(context, false)
        rocks.pkb.plektra.data.emoji.TestEmojiFont.install(context)
        val sym: rocks.pkb.plektra.core.SymLayoutController = getField(service, "symLayoutController")
        val own: AlternateCharacterManager = getField(service, "alternateCharacterManager")
        val ownQ = own.getSymMappings()[KeyEvent.KEYCODE_Q]
        sym.openEmojiPage()
        assertEquals(rocks.pkb.plektra.core.SymPagedLayer.PREVIOUS_LABEL, sym.currentSymMappings()!![KeyEvent.KEYCODE_Q])
        assertTrue(!ownQ.isNullOrEmpty())

        val t0 = 5_400L
        service.onKeyDown(KeyEvent.KEYCODE_SYM, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, t0, t0))
        service.onKeyDown(KeyEvent.KEYCODE_Q, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q, t0 + 30L, t0 + 30L))
        service.onKeyUp(KeyEvent.KEYCODE_Q, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Q, t0 + 30L, t0 + 60L))
        service.onKeyUp(KeyEvent.KEYCODE_SYM, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SYM, t0, t0 + 90L))

        assertEquals(listOf(ownQ), recorder.committedTexts)
        assertTrue("the chord leaves the page open", sym.isSymActive())
    }

    @Test
    fun heldSymBoundaryConsumesTappedShiftBeforeNextLetter() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        SettingsManager.setExperimentalSuggestionsEnabled(context, true)
        SettingsManager.setShiftTapLatches(context, false)
        SettingsManager.setSymPagesConfig(context, SymPagesConfig(deviceEnabled = false, emojiEnabled = false))
        focusNewField(RecordingInputConnection(), InputType.TYPE_CLASS_TEXT)

        val t0 = 4_800L
        tapShift(t0)
        assertTrue(modifierController().shiftOneShot)
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SYM,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, t0 + 40L, t0 + 40L)))
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_S,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, t0 + 60L, t0 + 60L)))
        assertEquals(listOf(";"), recorder.committedTexts)
        assertFalse("Boundary handling must consume the same one-shot as a direct SYM commit",
            modifierController().shiftOneShot)
        service.onKeyUp(KeyEvent.KEYCODE_S,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_S, t0 + 60L, t0 + 70L))
        service.onKeyUp(KeyEvent.KEYCODE_SYM,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SYM, t0 + 40L, t0 + 80L))
        service.onKeyDown(KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 100L, t0 + 100L))
        assertEquals(listOf(";", "a"), recorder.committedTexts)
    }

    @Test
    fun symPlusQuickLauncherShortcut_opensQuickLauncherFromEditableField() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setQuickLauncherTextFieldShortcuts(context, true)
        SettingsManager.setQuickLauncherShortcut(context, KeyEvent.KEYCODE_SPACE)
        val t0 = 4_200L

        val symHandled = service.onKeyDown(
            KeyEvent.KEYCODE_SYM,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, t0, t0)
        )
        val spaceHandled = service.onKeyDown(
            KeyEvent.KEYCODE_SPACE,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, t0, t0 + 40L)
        )

        val startedIntent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertTrue(symHandled)
        assertTrue(spaceHandled)
        assertEquals(QuickLauncherActivity::class.java.name, startedIntent.component?.className)
        assertTrue(startedIntent.getBooleanExtra(QuickLauncherActivity.EXTRA_TOGGLE_REQUEST, false))
        assertFalse("Space should not be committed when opening QuickLauncher", recorder.committedTexts.contains(" "))
    }

    @Test
    fun heldSymPlusQuickLauncherShortcut_reopensFromEditableFieldAfterLauncherCloses() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setQuickLauncherTextFieldShortcuts(context, true)
        SettingsManager.setQuickLauncherShortcut(context, KeyEvent.KEYCODE_SPACE)
        val t0 = 4_600L

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_SPACE,
            keyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_SPACE,
                t0,
                t0 + 40L,
                KeyEvent.META_SYM_ON
            )
        )

        val startedIntent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertTrue(handled)
        assertEquals(QuickLauncherActivity::class.java.name, startedIntent.component?.className)
        assertTrue(startedIntent.getBooleanExtra(QuickLauncherActivity.EXTRA_TOGGLE_REQUEST, false))
        assertFalse("Space should not be committed when reopening QuickLauncher", recorder.committedTexts.contains(" "))
    }

    @Test
    fun vietnameseTelexLayout_rewritesThroughImePath() {
        LayoutMappingRepository.loadLayout(service.assets, "vietnamese_telex_qwerty", service)
        setField(service, "activeKeyboardLayoutName", "vietnamese_telex_qwerty")
        recorder.textBeforeCursor = "ca"

        val handled = service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 5_000L, 5_000L)
        )

        assertTrue(handled)
        assertTrue(recorder.deleteSurroundingTextCalls.contains(2 to 0))
        assertTrue("commits=${recorder.committedTexts}", recorder.committedTexts.contains("câ"))
    }

    @Test
    fun vietnameseTelexLayout_ctrlA_shortcutWinsOverTelexRewrite_midWord() {
        LayoutMappingRepository.loadLayout(service.assets, "vietnamese_telex_qwerty", service)
        setField(service, "activeKeyboardLayoutName", "vietnamese_telex_qwerty")
        recorder.textBeforeCursor = "ca"

        tapCtrl(5_200L) // ctrl one-shot
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 5_260L, 5_260L)
        )

        assertTrue(
            "Expected shortcut path, commits=${recorder.committedTexts}, deleteCalls=${recorder.deleteSurroundingTextCalls}",
            recorder.contextMenuActions.contains(android.R.id.selectAll) || recorder.sentKeyEvents.isNotEmpty()
        )
        assertTrue("Telex rewrite should not delete text", recorder.deleteSurroundingTextCalls.isEmpty())
        assertFalse("Telex rewrite should not commit transformed text", recorder.committedTexts.contains("câ"))
    }

    @Test
    fun vietnameseTelexLayout_physicalCtrlHold_shortcutWinsWhenEventCtrlMetaMissing() {
        LayoutMappingRepository.loadLayout(service.assets, "vietnamese_telex_qwerty", service)
        setField(service, "activeKeyboardLayoutName", "vietnamese_telex_qwerty")
        recorder.textBeforeCursor = "ca"

        val t0 = 5_400L
        service.onKeyDown(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, t0, t0)
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_A,
                downTime = t0,
                eventTime = t0 + 80L,
                metaState = 0
            )
        )

        assertTrue(
            "Expected shortcut path with physical Ctrl fallback, commits=${recorder.committedTexts}, deleteCalls=${recorder.deleteSurroundingTextCalls}",
            recorder.contextMenuActions.contains(android.R.id.selectAll) || recorder.sentKeyEvents.isNotEmpty()
        )
        assertTrue("Telex rewrite should not delete text", recorder.deleteSurroundingTextCalls.isEmpty())
        assertFalse("Telex rewrite should not commit transformed text", recorder.committedTexts.contains("câ"))
    }

    private fun tapAlt(start: Long) {
        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, start, start)
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, start, start + 30L)
        )
    }

    private fun tapShift(start: Long) {
        service.onKeyDown(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, start, start)
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, start, start + 30L)
        )
    }

    private fun tapCtrl(start: Long) {
        service.onKeyDown(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, start, start)
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, start, start + 30L)
        )
    }

    private fun typeClicksSyntheticAtMacro(start: Long) {
        val shiftMeta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        service.onKeyDown(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_SHIFT_LEFT,
                downTime = start,
                eventTime = start,
                metaState = shiftMeta
            )
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_2,
            keyEvent(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_2,
                downTime = start,
                eventTime = start,
                metaState = shiftMeta
            )
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_2,
            keyEvent(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_2,
                downTime = start,
                eventTime = start + 90L,
                metaState = shiftMeta
            )
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            keyEvent(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_SHIFT_LEFT,
                downTime = start,
                eventTime = start + 120L
            )
        )
    }

    private fun tapSym(start: Long) {
        service.onKeyDown(
            63,
            keyEvent(KeyEvent.ACTION_DOWN, 63, start, start)
        )
        service.onKeyUp(
            63,
            keyEvent(KeyEvent.ACTION_UP, 63, start, start + 20L)
        )
    }

    @Test
    fun rawMode_plainFieldDoesNotCapitalize() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setRawModeApp(context, "rocks.pkb.plektra.test", true)
        focusNewField(RecordingInputConnection(), inputType = InputType.TYPE_CLASS_TEXT)
        assertFalse(modifierController().shiftOneShot)
    }

    @Test
    fun rawMode_fieldCapitalizationFlagsDoNotArmShiftOrCapsLock() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setRawModeApp(context, "rocks.pkb.plektra.test", true)
        for (flag in listOf(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, InputType.TYPE_TEXT_FLAG_CAP_WORDS, InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)) {
            focusNewField(RecordingInputConnection(), inputType = InputType.TYPE_CLASS_TEXT or flag)
            assertFalse("Shift for flag $flag", modifierController().shiftOneShot)
            assertFalse("Caps Lock for flag $flag", modifierController().capsLockEnabled)
            val state = InputContextState.fromEditorInfo(editorInfo, isRawModeApp = true)
            assertFalse(AutoCapitalizeHelper.shouldCapitalizeAfterBoundary(context, state, inputConnection, KeyEvent.KEYCODE_SPACE))
        }
    }

    @Test
    fun rawMode_fieldRestrictionsDoNotAllowAutoCapitalizeOptIn() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setRawModeApp(context, "rocks.pkb.plektra.test", true)
        SettingsManager.setAutoCapitalizeRestrictedFields(context, true)
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_FILTER, InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
            focusNewField(RecordingInputConnection(), inputType = InputType.TYPE_CLASS_TEXT or variation)
            assertFalse("Shift for variation $variation", modifierController().shiftOneShot)
        }
    }

    // ---- User key remaps: "Swap right Shift and Enter" on the Titan 2 Elite ----
    // Physical keys by Linux scan code: Enter 28, right Shift 54, left Alt 56, A 30, Tab 15.

    private val rightShiftMeta = KeyEvent.META_SHIFT_RIGHT_ON or KeyEvent.META_SHIFT_ON
    private var remapClock = 20_000L

    private fun tick(ms: Long = 100L): Long {
        remapClock += ms
        return remapClock
    }

    /** A Titan 2 Elite whose keys come from a built-in keyboard device, with the swap switched on. */
    private fun enableShiftEnterSwap() {
        useTitan2EliteBuiltInKeyboard()
        assertTrue(
            SettingsManager.setKeyRemapPresetEnabled(
                RuntimeEnvironment.getApplication(),
                HardwareKeyRemapPresets.SWAP_SHIFT_RIGHT_ENTER,
                true
            )
        )
    }

    /** A Titan 2 Elite reports itself as a Titan 2; its board and display name tell it apart. */
    private fun useTitan2EliteBuiltInKeyboard() {
        DeviceSpecific.setBuildFingerprintForTests(
            brand = "unihertz",
            manufacturer = "unihertz",
            model = "Titan 2",
            device = "Titan_2",
            product = "Titan_2_EEA",
            board = "G72BoardV1",
            display = "Titan 2 Elite"
        )
        setField(service, "physicalKeyboardProfileOverride", "auto")
        FakeKeyboards.register()
    }

    // The vendor Key setting makes physical right Shift (scan 54) arrive as Alt: with the swap on,
    // scan 54 must act as Enter, and the physical Enter key (scan 28) as Shift, in an app on the
    // messenger preset.
    private fun enableSwapOnVendorShiftAsAlt() {
        enableShiftEnterSwap()
        focusNewField(
            newRecorder = RecordingInputConnection(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            packageName = "com.beeper.android"
        )
        recorder.performEditorActionResult = true
    }

    /** The swap in a plain QWERTY text field where nothing but a held Shift capitalizes. */
    private fun enableSwapInPlainTextField() {
        enableShiftEnterSwap()
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT
        useLayout("qwerty")
    }

    private fun plainTextField() = focusNewField(RecordingInputConnection(), InputType.TYPE_CLASS_TEXT)

    /**
     * A field without an editor (a terminal): the IME leaves Enter, Tab and Shift to the app.
     * [fieldId] is the id of its view, 0 for a view the framework reports none for.
     */
    private fun keyEventField(
        connection: RecordingInputConnection = RecordingInputConnection(),
        fieldId: Int = 0,
        packageName: String = "com.example.remote"
    ): RecordingInputConnection {
        SettingsManager.setPowerShortcutsEnabled(RuntimeEnvironment.getApplication(), false)
        focusNewField(connection, EditorInfo.TYPE_NULL, packageName, fieldId)
        return connection
    }

    /** An id as a view declared in a layout has; the framework reports it as the field id. */
    private val viewId = 0x7f0a0101

    /**
     * The framework starts input without finishing the previous one, as it does on the device:
     * it installs [connection] (null: the start has none) and a new EditorInfo as the current
     * ones first, and calls onStartInput after that.
     */
    private fun startInput(
        connection: InputConnection?,
        restarting: Boolean,
        packageName: String? = editorInfo.packageName,
        fieldId: Int = editorInfo.fieldId
    ) {
        val previous = editorInfo
        editorInfo = EditorInfo().apply {
            inputType = previous.inputType
            this.packageName = packageName
            this.fieldId = fieldId
        }
        setField(service, "mInputConnection", connection)
        setField(service, "mStartedInputConnection", connection)
        setField(service, "mInputEditorInfo", editorInfo)
        service.onStartInput(editorInfo, restarting)
    }

    /**
     * The framework starts input again without finishing it (the client already had an editor):
     * the IME gets another connection, for the same view or for another one. The editor keeps
     * its package and field id unless the test names others.
     */
    private fun restartInputWithNewConnection(
        connection: RecordingInputConnection = RecordingInputConnection(),
        packageName: String? = editorInfo.packageName,
        fieldId: Int = editorInfo.fieldId,
        restarting: Boolean = true
    ): RecordingInputConnection {
        recorder = connection
        inputConnection = connection.asProxy()
        startInput(inputConnection, restarting, packageName, fieldId)
        return connection
    }

    /** Key code, action, repeat count and cancel flag of each event. */
    private fun presses(events: List<KeyEvent>): List<String> = events.map {
        val action = if (it.action == KeyEvent.ACTION_DOWN) "down" else "up"
        "${it.keyCode} $action repeat=${it.repeatCount}" + if (it.isCanceled) " cancelled" else ""
    }

    private fun remapper(): HardwareKeyRemapper = getField(service, "hardwareKeyRemapper")

    /** The physical Enter key of the built-in keyboard (scan 28), as the remapper names it. */
    private fun enterKeyId(): Long =
        HardwareKeyRemapper.physicalKeyId(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, 0L, 0L))

    private fun scanKey(
        action: Int,
        keyCode: Int,
        scanCode: Int,
        downTime: Long,
        eventTime: Long,
        metaState: Int = 0,
        repeatCount: Int = 0,
        deviceId: Int = FakeKeyboards.BUILT_IN,
        flags: Int = KeyEvent.FLAG_FROM_SYSTEM
    ): KeyEvent = KeyEvent(
        downTime, eventTime, action, keyCode, repeatCount, metaState, deviceId, scanCode, flags,
        InputDevice.SOURCE_KEYBOARD
    )

    /** Types the A key of the built-in keyboard and returns what it committed. */
    private fun typeA(metaState: Int = 0): String {
        recorder.committedTexts.clear()
        val time = tick()
        service.onKeyDown(
            KeyEvent.KEYCODE_A,
            scanKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 30, time, time, metaState)
        )
        service.onKeyUp(
            KeyEvent.KEYCODE_A,
            scanKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 30, time, time + 20L, metaState)
        )
        return recorder.committedTexts.joinToString("")
    }

    private fun enterKey(
        action: Int,
        downTime: Long,
        eventTime: Long,
        repeatCount: Int = 0,
        deviceId: Int = FakeKeyboards.BUILT_IN,
        flags: Int = KeyEvent.FLAG_FROM_SYSTEM
    ): KeyEvent = scanKey(
        action, KeyEvent.KEYCODE_ENTER, 28, downTime, eventTime,
        repeatCount = repeatCount, deviceId = deviceId, flags = flags
    )

    private fun rightShiftKey(action: Int, downTime: Long, eventTime: Long, repeatCount: Int = 0): KeyEvent =
        scanKey(
            action, KeyEvent.KEYCODE_SHIFT_RIGHT, 54, downTime, eventTime,
            metaState = if (action == KeyEvent.ACTION_UP) 0 else rightShiftMeta,
            repeatCount = repeatCount
        )

    private fun sentKeys(connection: RecordingInputConnection): List<String> = keys(connection.sentKeyEvents)

    private fun keys(events: List<KeyEvent>): List<String> =
        events.map {
            val action = if (it.action == KeyEvent.ACTION_DOWN) "down" else "up"
            val key = when (it.keyCode) {
                KeyEvent.KEYCODE_ENTER -> "ENTER"
                KeyEvent.KEYCODE_TAB -> "TAB"
                KeyEvent.KEYCODE_SHIFT_RIGHT -> "SHIFT_RIGHT"
                else -> it.keyCode.toString()
            }
            "$key $action repeat=${it.repeatCount} shift=${it.isShiftPressed}"
        }

    @Test
    fun swapOn_vendorRewrittenRightShiftAlone_sendsInMessengerApp() {
        enableSwapOnVendorShiftAsAlt()

        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_RIGHT,
            scanKey(
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT, 54, 9_000L, 9_000L,
                KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_ALT_ON
            )
        )

        assertEquals(
            "sent=${recorder.editorActions} text=${recorder.committedTexts}",
            listOf(EditorInfo.IME_ACTION_SEND),
            recorder.editorActions
        )
        assertFalse(recorder.committedTexts.contains("\n"))
    }

    @Test
    fun swapOn_heldPhysicalEnterThenRightShift_isNewlineInMessengerApp() {
        enableSwapOnVendorShiftAsAlt()

        // Physical Enter (scan 28) is held and acts as Shift.
        service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, 9_100L, 9_100L))
        // Physical right Shift (scan 54, arrives as Alt) acts as Enter.
        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_RIGHT,
            scanKey(
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT, 54, 9_100L, 9_200L,
                KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_ALT_ON
            )
        )

        assertTrue(
            "text=${recorder.committedTexts} sent=${recorder.editorActions}",
            recorder.committedTexts.contains("\n")
        )
        assertTrue(recorder.editorActions.isEmpty())
    }

    @Test
    fun swapOn_heldVendorShiftThenRightShift_isNewlineInMessengerApp() {
        enableSwapOnVendorShiftAsAlt()

        // Physical Alt (scan 56) arrives as Shift through the vendor Key setting.
        service.onKeyDown(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            scanKey(
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 56, 9_300L, 9_300L,
                KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON
            )
        )
        service.onKeyDown(
            KeyEvent.KEYCODE_ALT_RIGHT,
            scanKey(
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT, 54, 9_300L, 9_400L,
                KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON or
                    KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_ALT_ON
            )
        )

        assertTrue(
            "text=${recorder.committedTexts} sent=${recorder.editorActions}",
            recorder.committedTexts.contains("\n")
        )
        assertTrue(recorder.editorActions.isEmpty())
    }

    @Test
    fun swapOn_anotherKeyThatSendsRightShift_staysAShift() {
        enableSwapInPlainTextField()
        // The vendor Key setting may give another key (here the Alt key, scan 100) the right
        // Shift's key code: only the key at scan 54 is the one the swap turns into Enter.
        val down = tick()
        service.onKeyDown(
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            scanKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, 100, down, down, rightShiftMeta)
        )
        assertEquals("A", typeA(rightShiftMeta))
        service.onKeyUp(
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            scanKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_RIGHT, 100, down, tick())
        )
        assertEquals("a", typeA())
        assertTrue(recorder.editorActions.isEmpty())
        assertTrue(sentKeys(recorder).toString(), recorder.sentKeyEvents.isEmpty())
    }

    @Test
    fun swapOn_tappedEnterArmsTheUsersOneShotShift_soTheNextEnterIsANewline() {
        enableSwapOnVendorShiftAsAlt()
        modifierController().consumeShiftOneShot()

        // Physical Enter tapped alone: a Shift tap.
        val tap = tick()
        service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, tap, tap))
        service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, tap, tap + 40L))
        assertTrue(recorder.editorActions.isEmpty())
        assertTrue(modifierController().shiftOneShot)
        assertTrue(modifierController().shiftOneShotFromUserTap)

        // The Enter that follows (physical right Shift) is Shift+Enter: a newline, not a send.
        val enter = tick(200L)
        service.onKeyDown(
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            rightShiftKey(KeyEvent.ACTION_DOWN, enter, enter)
        )
        assertTrue(
            "text=${recorder.committedTexts} sent=${recorder.editorActions}",
            recorder.committedTexts.contains("\n")
        )
        assertTrue(recorder.editorActions.isEmpty())
    }

    @Test
    fun swapOn_appliesToTheBuiltInKeyboardOnly() {
        enableSwapOnVendorShiftAsAlt()
        fun enterSends(down: KeyEvent, up: KeyEvent): Boolean {
            recorder.editorActions.clear()
            service.onKeyDown(KeyEvent.KEYCODE_ENTER, down)
            service.onKeyUp(KeyEvent.KEYCODE_ENTER, up)
            return recorder.editorActions == listOf(EditorInfo.IME_ACTION_SEND)
        }

        var time = tick()
        assertTrue(
            "accessory keyboard",
            enterSends(
                enterKey(KeyEvent.ACTION_DOWN, time, time, deviceId = FakeKeyboards.ACCESSORY),
                enterKey(KeyEvent.ACTION_UP, time, time + 20L, deviceId = FakeKeyboards.ACCESSORY)
            )
        )
        time = tick(500L)
        assertTrue(
            "event without a device",
            enterSends(
                keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, time, time),
                keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, time, time + 20L)
            )
        )
        time = tick(500L)
        assertTrue(
            "on-screen key",
            enterSends(
                enterKey(KeyEvent.ACTION_DOWN, time, time, flags = KeyEvent.FLAG_SOFT_KEYBOARD),
                enterKey(KeyEvent.ACTION_UP, time, time + 20L, flags = KeyEvent.FLAG_SOFT_KEYBOARD)
            )
        )
        // The control: the same key from the built-in keyboard acts as Shift.
        time = tick(500L)
        assertFalse(
            "built-in keyboard",
            enterSends(enterKey(KeyEvent.ACTION_DOWN, time, time), enterKey(KeyEvent.ACTION_UP, time, time + 20L))
        )
    }

    @Test
    fun swapOn_heldEnterKeepsShiftingThroughEditorChanges() {
        enableSwapInPlainTextField()
        assertEquals("a", typeA())

        val down = tick()
        service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down))
        assertEquals("A", typeA())

        service.onStartInput(editorInfo, true)
        assertEquals("after a restarting start", "A", typeA())
        service.onFinishInput()
        plainTextField()
        assertEquals("in the next field", "A", typeA())
        service.onFinishInputView(false)
        service.onWindowHidden()
        assertEquals("with the keyboard hidden", "A", typeA())

        service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick()))
        assertEquals("a", typeA())
        assertTrue(recorder.editorActions.isEmpty())
        assertTrue(sentKeys(recorder).toString(), recorder.sentKeyEvents.isEmpty())
    }

    @Test
    fun swapOn_heldEnterRepeatingInTheNextEditor_armsNoOneShotShift() {
        enableSwapInPlainTextField()
        val down = tick()
        service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down))
        assertEquals("A", typeA())

        // The new editor resets the modifiers; the key is still down as Shift.
        service.onFinishInput()
        plainTextField()
        assertTrue(modifierController().shiftPressed)
        assertTrue(modifierController().shiftPhysicallyPressed)

        // Its repeat is no new press and its release no tap: nothing is left armed.
        service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, tick(), repeatCount = 1))
        assertFalse(modifierController().shiftOneShot)
        service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick()))
        assertFalse(modifierController().shiftOneShot)
        assertFalse(modifierController().shiftPressed)
        assertFalse(modifierController().shiftPhysicallyPressed)
        assertEquals("a", typeA())
    }

    @Test
    fun swapOn_heldEnterReleasedInAFieldWithoutAnEditor_leavesNoShiftPressed() {
        enableSwapInPlainTextField()
        val down = tick()
        service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down))
        assertEquals("A", typeA())

        // Focus moves to a view that reads keys itself, where the IME releases no modifiers.
        service.onFinishInput()
        keyEventField()
        assertTrue(modifierController().shiftPressed)
        service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick()))
        assertFalse(modifierController().shiftPressed)
        assertFalse(modifierController().shiftPhysicallyPressed)

        service.onFinishInput()
        plainTextField()
        assertFalse(modifierController().shiftOneShot)
        assertEquals("a", typeA())
    }

    @Test
    fun swapOn_heldEnterEndsWhereTheClientUnbinds_andTheRestOfThePressIsSwallowed() {
        enableSwapInPlainTextField()
        val down = tick()
        service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down))
        assertEquals("A", typeA())

        service.onUnbindInput()
        plainTextField()
        assertEquals("a", typeA())
        assertFalse(modifierController().shiftPressed)
        assertFalse(modifierController().shiftPhysicallyPressed)

        // The key is still down: its repeat and its UP are neither Shift nor a raw Enter.
        recorder.committedTexts.clear()
        assertTrue(
            service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, tick(), repeatCount = 1))
        )
        assertEquals("a", typeA())
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        assertFalse(modifierController().shiftOneShot)
        assertTrue(recorder.editorActions.isEmpty())
        assertTrue(sentKeys(recorder).toString(), recorder.sentKeyEvents.isEmpty())

        // The next press is a Shift again.
        val again = tick()
        service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, again, again))
        assertEquals("A", typeA())
        service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, again, tick()))
    }

    @Test
    fun swapOn_rightShiftCutOffByAnUnbind_doesNotTurnIntoAShift() {
        enableSwapInPlainTextField()
        val down = tick()
        // Physical right Shift goes down as Enter, and the client unbinds under it.
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, down, down))
        service.onUnbindInput()
        plainTextField()

        // The framework goes on reporting the held Shift; the key never acted as one.
        assertEquals("a", typeA(rightShiftMeta))
        // Another Shift held with it still shifts.
        assertEquals("A", typeA(rightShiftMeta or KeyEvent.META_SHIFT_LEFT_ON))
        recorder.committedTexts.clear()
        assertTrue(
            service.onKeyDown(
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                rightShiftKey(KeyEvent.ACTION_DOWN, down, tick(), repeatCount = 1)
            )
        )
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, down, tick())))
        assertTrue(recorder.committedTexts.toString(), recorder.committedTexts.isEmpty())
        assertTrue(recorder.editorActions.isEmpty())
        assertTrue(sentKeys(recorder).toString(), recorder.sentKeyEvents.isEmpty())
        assertEquals("a", typeA())
    }

    @Test
    fun swapOn_heldRightShiftRepeatsEnterIntoTheNextField_withoutAnOrphanUp() {
        enableShiftEnterSwap()
        val first = keyEventField()
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, down, down)))
        assertEquals(listOf("ENTER down repeat=0 shift=false"), sentKeys(first))

        // Tab while the key is held: the app must see Tab, not Shift+Tab.
        val tab = tick()
        assertTrue(
            service.onKeyDown(
                KeyEvent.KEYCODE_TAB,
                scanKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 15, tab, tab, rightShiftMeta)
            )
        )
        assertTrue(
            service.onKeyUp(
                KeyEvent.KEYCODE_TAB,
                scanKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 15, tab, tab + 20L, rightShiftMeta)
            )
        )
        assertEquals(
            listOf(
                "ENTER down repeat=0 shift=false",
                "TAB down repeat=0 shift=false",
                "TAB up repeat=0 shift=false"
            ),
            sentKeys(first)
        )

        // Focus moved: the held key repeats into the new field and is released there.
        val second = keyEventField()
        assertTrue(
            service.onKeyDown(
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                rightShiftKey(KeyEvent.ACTION_DOWN, down, tick(), repeatCount = 1)
            )
        )
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, down, tick())))
        assertEquals(
            listOf("ENTER down repeat=1 shift=false", "ENTER up repeat=0 shift=false"),
            sentKeys(second)
        )
        // The field it left got its release, as a cancelled press, and nothing after it.
        assertEquals(4, first.sentKeyEvents.size)
        assertEquals("ENTER up repeat=0 shift=false", sentKeys(first)[3])
        assertTrue(first.sentKeyEvents[3].isCanceled)

        // Released in a field that never saw it go down: the UP goes nowhere.
        val again = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, again, again)))
        assertEquals(3, second.sentKeyEvents.size)
        val third = keyEventField()
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, again, tick())))
        assertTrue(sentKeys(third).toString(), third.sentKeyEvents.isEmpty())
        assertEquals(4, second.sentKeyEvents.size)
        assertTrue(second.sentKeyEvents[3].isCanceled)
    }

    @Test
    fun swapOn_keyHeldOverARestartIntoAnotherView_endsWhereItWentDown_andSendsNoUpIntoTheNewOne() {
        enableShiftEnterSwap()
        val firstView = mutableListOf<KeyEvent>()
        val secondView = mutableListOf<KeyEvent>()
        val thirdView = mutableListOf<KeyEvent>()
        keyEventField(RecordingInputConnection(firstView))

        // Right Shift as Enter. Focus moves inside the window: a restarting start, another view.
        val shift = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, shift, shift)))
        restartInputWithNewConnection(RecordingInputConnection(secondView))
        assertEquals(
            listOf("ENTER down repeat=0 shift=false", "ENTER up repeat=0 shift=false"),
            keys(firstView)
        )
        assertFalse(firstView[0].isCanceled)
        assertTrue(firstView[1].isCanceled)
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, shift, tick())))
        assertEquals(2, firstView.size)
        assertTrue(keys(secondView).toString(), secondView.isEmpty())

        // Enter as Shift.
        val enter = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, enter, enter)))
        restartInputWithNewConnection(RecordingInputConnection(thirdView))
        assertEquals(
            listOf("SHIFT_RIGHT down repeat=0 shift=true", "SHIFT_RIGHT up repeat=0 shift=false"),
            keys(secondView)
        )
        assertTrue(secondView[1].isCanceled)
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, enter, tick())))
        assertEquals(2, secondView.size)
        assertTrue(keys(thirdView).toString(), thirdView.isEmpty())
    }

    @Test
    fun swapOn_keyHeldOverARestartOfTheSameView_endsThereAsCancelled_once() {
        enableShiftEnterSwap()
        // One view, a connection per start: each sends its key events to that view.
        val view = mutableListOf<KeyEvent>()
        keyEventField(RecordingInputConnection(view))

        // Right Shift as Enter, released before it repeats.
        val shift = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, shift, shift)))
        tick()
        val second = restartInputWithNewConnection(RecordingInputConnection(view))
        assertEquals(listOf("ENTER down repeat=0 shift=false", "ENTER up repeat=0 shift=false"), keys(view))
        // The release is the DOWN's own: its key, down time, device and scan code, cancelled.
        val release = view[1]
        assertTrue(release.isCanceled)
        assertEquals(shift, release.downTime)
        assertEquals(FakeKeyboards.BUILT_IN, release.deviceId)
        assertEquals(54, release.scanCode)
        assertEquals(InputDevice.SOURCE_KEYBOARD, release.source)
        // It went through the connection that took the DOWN, not through the new one.
        assertTrue(sentKeys(second).toString(), second.sentKeyEvents.isEmpty())
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, shift, tick())))
        assertEquals("the view sees one release", 2, view.size)

        // Enter as Shift, held on: its repeat brings it back to the view, and its UP ends that.
        view.clear()
        val enter = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, enter, enter)))
        restartInputWithNewConnection(RecordingInputConnection(view))
        assertTrue(
            service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, enter, tick(), repeatCount = 1))
        )
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, enter, tick())))
        assertEquals(
            listOf(
                "SHIFT_RIGHT down repeat=0 shift=true",
                "SHIFT_RIGHT up repeat=0 shift=false",
                "SHIFT_RIGHT down repeat=1 shift=true",
                "SHIFT_RIGHT up repeat=0 shift=false"
            ),
            keys(view)
        )
        assertEquals(listOf(false, true, false, false), view.map { it.isCanceled })
        assertEquals(listOf(enter, enter, enter, enter), view.map { it.downTime })
        assertEquals(listOf(28, 28, 28, 28), view.map { it.scanCode })
    }

    @Test
    fun swapOn_releaseThePreviousConnectionDoesNotTake_isNotRetriedInTheNewOne_andTheKeyStaysHeld() {
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        // A view without an id: the IME has nothing to take a restart of the same field by.
        val first = keyEventField(RecordingInputConnection(view))

        // Enter as Shift goes down; the view starts input again and its old connection refuses.
        val enter = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, enter, enter)))
        first.sendKeyEventResult = false
        val second = restartInputWithNewConnection(RecordingInputConnection(view))
        // One attempt, through the connection that took the DOWN.
        assertEquals(
            listOf("SHIFT_RIGHT down repeat=0 shift=true", "SHIFT_RIGHT up repeat=0 shift=false"),
            sentKeys(first)
        )
        assertEquals(listOf("SHIFT_RIGHT down repeat=0 shift=true"), keys(view))
        assertTrue(sentKeys(second).toString(), second.sentKeyEvents.isEmpty())

        // The key is still held and still a Shift: a Tab arrives shifted.
        val tab = tick()
        assertTrue(
            service.onKeyDown(KeyEvent.KEYCODE_TAB, scanKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 15, tab, tab))
        )
        assertTrue(
            service.onKeyUp(KeyEvent.KEYCODE_TAB, scanKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 15, tab, tab + 20L))
        )
        assertEquals(listOf("TAB down repeat=0 shift=true", "TAB up repeat=0 shift=true"), sentKeys(second))

        // Its UP goes nowhere: the new connection saw no DOWN, the old one is not tried again.
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, enter, tick())))
        assertEquals(2, first.sentKeyEvents.size)
        assertEquals(2, second.sentKeyEvents.size)

        // The same when the old connection takes the release and the app drops it, as the
        // framework does for a connection the app has replaced: the IME cannot tell, and the
        // view keeps the press until the key comes back to it.
        view.clear()
        val shift = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, shift, shift)))
        second.replacedByTheApp = true
        val third = restartInputWithNewConnection(RecordingInputConnection(view))
        assertEquals(listOf("ENTER down repeat=0 shift=false"), keys(view))
        assertTrue(sentKeys(third).toString(), third.sentKeyEvents.isEmpty())
        assertTrue(
            service.onKeyDown(
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                rightShiftKey(KeyEvent.ACTION_DOWN, shift, tick(), repeatCount = 1)
            )
        )
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, shift, tick())))
        assertEquals(
            listOf(
                "ENTER down repeat=0 shift=false",
                "ENTER down repeat=1 shift=false",
                "ENTER up repeat=0 shift=false"
            ),
            keys(view)
        )
        assertFalse(view[2].isCanceled)
    }

    private fun metaOf(modifier: Int): Int = when (modifier) {
        KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON
        KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_RIGHT_ON or KeyEvent.META_SHIFT_ON
        KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON
        KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_RIGHT_ON or KeyEvent.META_CTRL_ON
        KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_ON
        KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_ALT_ON
        else -> error("not a Shift, Ctrl or Alt key: $modifier")
    }

    /**
     * The Enter key (scan 28) remapped to the modifier [target], held in a view that reads keys
     * itself and has an id. The view starts its input again: the app has replaced the connection
     * the key went down through, which takes what the IME sends and drops it, as the framework
     * does. The release goes through the connection of the restart instead, once.
     */
    private fun heldModifierIsReleasedThroughTheConnectionOfARestartOfItsEditor(target: Int) {
        remapEnterTo(target)
        // With nav mode on the IME keeps a Ctrl key to itself outside text fields: the app gets
        // no Ctrl DOWN from it, and there is nothing to release.
        SettingsManager.setNavModeEnabled(RuntimeEnvironment.getApplication(), false)
        val view = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(view), fieldId = viewId)

        // Released before it repeats.
        val down = tick(1_000L)
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))
        assertEquals(listOf("$target down repeat=0"), presses(view))
        first.replacedByTheApp = true
        tick()
        val second = restartInputWithNewConnection(RecordingInputConnection(view))
        assertEquals(listOf("$target down repeat=0", "$target up repeat=0 cancelled"), presses(view))
        // One release, and through one connection: the old one got nothing after the DOWN.
        assertEquals(listOf("$target down repeat=0"), presses(first.sentKeyEvents))
        assertEquals(listOf("$target up repeat=0 cancelled"), presses(second.sentKeyEvents))
        // It is the DOWN's own release: its down time, device, scan code and source, and its
        // meta state without the key's own bits.
        val pressed = view[0]
        val release = view[1]
        assertEquals(down, release.downTime)
        assertEquals(FakeKeyboards.BUILT_IN, release.deviceId)
        assertEquals(28, release.scanCode)
        assertEquals(InputDevice.SOURCE_KEYBOARD, release.source)
        assertEquals(metaOf(target), pressed.metaState and metaOf(target))
        assertEquals(0, release.metaState and metaOf(target))
        // The key itself is still held, as the modifier.
        assertEquals(target, remapper().heldTargetOf(enterKeyId()))

        // A second restart owes the view nothing, and neither does the key's own UP.
        second.replacedByTheApp = true
        val third = restartInputWithNewConnection(RecordingInputConnection(view))
        assertEquals(target, remapper().heldTargetOf(enterKeyId()))
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        assertEquals(2, view.size)
        assertEquals(1, first.sentKeyEvents.size)
        assertEquals(1, second.sentKeyEvents.size)
        assertTrue(presses(third.sentKeyEvents).toString(), third.sentKeyEvents.isEmpty())
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, remapper().heldTargetOf(enterKeyId()))

        // Held on instead: its repeat is a DOWN in the new connection, and its UP ends that.
        view.clear()
        val again = tick(1_000L)
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, again, again)))
        third.replacedByTheApp = true
        val fourth = restartInputWithNewConnection(RecordingInputConnection(view))
        assertTrue(
            service.onKeyDown(
                KeyEvent.KEYCODE_ENTER,
                enterKey(KeyEvent.ACTION_DOWN, again, tick(), repeatCount = 1)
            )
        )
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, again, tick())))
        assertEquals(
            listOf(
                "$target down repeat=0",
                "$target up repeat=0 cancelled",
                "$target down repeat=1",
                "$target up repeat=0"
            ),
            presses(view)
        )
        assertEquals(listOf(again, again, again, again), view.map { it.downTime })
        assertEquals(listOf("$target down repeat=0"), presses(third.sentKeyEvents))
        assertEquals(3, fourth.sentKeyEvents.size)
    }

    @Test
    fun keyRemappedToLeftShift_heldOverARestartOfItsEditor_isReleasedThroughTheNewConnection_once() =
        heldModifierIsReleasedThroughTheConnectionOfARestartOfItsEditor(KeyEvent.KEYCODE_SHIFT_LEFT)

    @Test
    fun keyRemappedToRightShift_heldOverARestartOfItsEditor_isReleasedThroughTheNewConnection_once() =
        heldModifierIsReleasedThroughTheConnectionOfARestartOfItsEditor(KeyEvent.KEYCODE_SHIFT_RIGHT)

    @Test
    fun keyRemappedToLeftCtrl_heldOverARestartOfItsEditor_isReleasedThroughTheNewConnection_once() =
        heldModifierIsReleasedThroughTheConnectionOfARestartOfItsEditor(KeyEvent.KEYCODE_CTRL_LEFT)

    @Test
    fun keyRemappedToRightCtrl_heldOverARestartOfItsEditor_isReleasedThroughTheNewConnection_once() =
        heldModifierIsReleasedThroughTheConnectionOfARestartOfItsEditor(KeyEvent.KEYCODE_CTRL_RIGHT)

    @Test
    fun keyRemappedToLeftAlt_heldOverARestartOfItsEditor_isReleasedThroughTheNewConnection_once() =
        heldModifierIsReleasedThroughTheConnectionOfARestartOfItsEditor(KeyEvent.KEYCODE_ALT_LEFT)

    @Test
    fun keyRemappedToRightAlt_heldOverARestartOfItsEditor_isReleasedThroughTheNewConnection_once() =
        heldModifierIsReleasedThroughTheConnectionOfARestartOfItsEditor(KeyEvent.KEYCODE_ALT_RIGHT)

    /**
     * Enter as Shift, held in a view that reads keys ([packageName], [fieldId]) whose connection
     * the app then replaces. [change] is what happens to the editor next; it returns the
     * connection that came with it, if one did. The release kept the old route: one cancelled
     * UP into the connection the key went down through (which drops it), nothing into the next
     * one, and the key still held.
     */
    private fun heldShiftKeepsTheOldRouteOfItsRelease(
        packageName: String = "com.example.remote",
        fieldId: Int = viewId,
        change: (view: MutableList<KeyEvent>) -> RecordingInputConnection?
    ) {
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(view), fieldId, packageName)
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))
        first.replacedByTheApp = true

        val next = change(view)
        assertEquals(
            listOf("SHIFT_RIGHT down repeat=0 shift=true", "SHIFT_RIGHT up repeat=0 shift=false"),
            sentKeys(first)
        )
        assertTrue(first.sentKeyEvents[1].isCanceled)
        assertEquals(listOf("SHIFT_RIGHT down repeat=0 shift=true"), keys(view))
        assertTrue(next == null || next.sentKeyEvents.isEmpty())
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, remapper().heldTargetOf(enterKeyId()))

        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        assertEquals(2, first.sentKeyEvents.size)
        assertTrue(next == null || next.sentKeyEvents.isEmpty())
    }

    @Test
    fun heldShift_restartForAnotherPackage_keepsTheOldRouteOfItsRelease() =
        heldShiftKeepsTheOldRouteOfItsRelease { view ->
            restartInputWithNewConnection(RecordingInputConnection(view), packageName = "com.example.other")
        }

    @Test
    fun heldShift_restartForAnotherFieldId_keepsTheOldRouteOfItsRelease() =
        heldShiftKeepsTheOldRouteOfItsRelease { view ->
            restartInputWithNewConnection(RecordingInputConnection(view), fieldId = viewId + 1)
        }

    @Test
    fun heldShift_restartOfAFieldWithoutAnId_keepsTheOldRouteOfItsRelease() {
        // The same view as far as the test goes, and nothing for the IME to know it by: this
        // is the case the id filter leaves as it was.
        heldShiftKeepsTheOldRouteOfItsRelease(fieldId = 0) { view ->
            restartInputWithNewConnection(RecordingInputConnection(view))
        }
    }

    @Test
    fun heldShift_restartOfAFieldWhoseViewHasNoId_keepsTheOldRouteOfItsRelease() =
        heldShiftKeepsTheOldRouteOfItsRelease(fieldId = -1) { view ->
            restartInputWithNewConnection(RecordingInputConnection(view))
        }

    @Test
    fun heldShift_restartOfAFieldWithoutAPackage_keepsTheOldRouteOfItsRelease() =
        heldShiftKeepsTheOldRouteOfItsRelease(packageName = "") { view ->
            restartInputWithNewConnection(RecordingInputConnection(view))
        }

    @Test
    fun heldShift_startThatIsNotARestart_keepsTheOldRouteOfItsRelease() =
        heldShiftKeepsTheOldRouteOfItsRelease { view ->
            // The same package and field id, in a start the framework does not call restarting.
            restartInputWithNewConnection(RecordingInputConnection(view), restarting = false)
        }

    @Test
    fun heldShift_restartWithoutAConnection_keepsTheOldRouteOfItsRelease() =
        heldShiftKeepsTheOldRouteOfItsRelease {
            startInput(null, restarting = true)
            null
        }

    @Test
    fun heldShift_finishedInput_keepsTheOldRouteOfItsRelease_andTheNextStartOwesNothing() =
        heldShiftKeepsTheOldRouteOfItsRelease { view ->
            service.onFinishInput()
            // The editor comes back with the same ids, in a start that is not a restart.
            restartInputWithNewConnection(RecordingInputConnection(view), restarting = false)
        }

    @Test
    fun heldShift_thatWentDownAfterAFinish_keepsTheOldRouteOfItsRelease_thoughTheEditorLeftBehindMatches() {
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(view), fieldId = viewId)

        // Input is finished. The framework leaves the EditorInfo of the finished input in
        // place, and the client's connection: a key that goes down now is sent through it,
        // into no editor.
        service.onFinishInput()
        assertEquals(viewId, service.currentInputEditorInfo.fieldId)
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))
        assertEquals(listOf("SHIFT_RIGHT down repeat=0 shift=true"), sentKeys(first))
        first.replacedByTheApp = true

        // A restarting start for the package and field id the leftover names: it is not the
        // editor the key went down in, for there was none.
        val second = restartInputWithNewConnection(RecordingInputConnection(view))
        assertEquals(
            listOf("SHIFT_RIGHT down repeat=0 shift=true", "SHIFT_RIGHT up repeat=0 shift=false"),
            sentKeys(first)
        )
        assertTrue(first.sentKeyEvents[1].isCanceled)
        assertTrue(sentKeys(second).toString(), second.sentKeyEvents.isEmpty())
        assertEquals(listOf("SHIFT_RIGHT down repeat=0 shift=true"), keys(view))
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        assertTrue(sentKeys(second).toString(), second.sentKeyEvents.isEmpty())
    }

    @Test
    fun heldShift_isKnownByTheEditorItWentDownIn_whateverThatEditorInfoSaysLater() {
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(view), fieldId = viewId)
        val wentDownIn = editorInfo
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))
        first.replacedByTheApp = true

        // The EditorInfo object the ids were read from says something else afterwards. The
        // restart is for the ids it had when the key went down: the release goes into it.
        wentDownIn.packageName = "com.example.other"
        wentDownIn.fieldId = viewId + 1
        val second = restartInputWithNewConnection(
            RecordingInputConnection(view), packageName = "com.example.remote", fieldId = viewId
        )
        assertEquals(listOf("SHIFT_RIGHT down repeat=0 shift=true"), sentKeys(first))
        assertEquals(listOf("SHIFT_RIGHT up repeat=0 shift=false"), sentKeys(second))
        assertTrue(second.sentKeyEvents.single().isCanceled)
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        assertEquals(1, second.sentKeyEvents.size)

        // The other way round: ids the object takes on after the DOWN do not make a restart
        // for those ids a restart of the editor the key went down in.
        view.clear()
        second.sentKeyEvents.clear()
        val wentDownInNext = editorInfo
        val again = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, again, again)))
        second.replacedByTheApp = true
        wentDownInNext.packageName = "com.example.other"
        wentDownInNext.fieldId = viewId + 1
        val third = restartInputWithNewConnection(
            RecordingInputConnection(view), packageName = "com.example.other", fieldId = viewId + 1
        )
        assertEquals(
            listOf("SHIFT_RIGHT down repeat=0 shift=true", "SHIFT_RIGHT up repeat=0 shift=false"),
            sentKeys(second)
        )
        assertTrue(second.sentKeyEvents[1].isCanceled)
        assertTrue(sentKeys(third).toString(), third.sentKeyEvents.isEmpty())
        assertEquals(listOf("SHIFT_RIGHT down repeat=0 shift=true"), keys(view))
    }

    @Test
    fun heldShift_finishedInput_isReleasedAtTheFinish_throughTheConnectionItWentDownThrough() {
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(view), fieldId = viewId)
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))

        // A finish replaces no connection: the release is sent there and then, not left for
        // a start that may never come, and it arrives.
        service.onFinishInput()
        assertEquals(
            listOf("SHIFT_RIGHT down repeat=0 shift=true", "SHIFT_RIGHT up repeat=0 shift=false"),
            keys(view)
        )
        assertTrue(view[1].isCanceled)
        assertEquals(down, view[1].downTime)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, remapper().heldTargetOf(enterKeyId()))

        // Nothing is owed after it: not at the key's UP, and not at a start.
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        val second = restartInputWithNewConnection(RecordingInputConnection(view), restarting = false)
        assertEquals(2, first.sentKeyEvents.size)
        assertEquals(2, view.size)
        assertTrue(sentKeys(second).toString(), second.sentKeyEvents.isEmpty())
    }

    @Test
    fun debugBuild_logsTheRouteOfEachRelease_andWhatKeptItOutOfTheNewConnection() {
        fun keyDeliveryLog(): List<String> =
            org.robolectric.shadows.ShadowLog.getLogsForTag("PlektraInputMethod")
                .map { it.msg }
                .filter { it.startsWith("keyDelivery: ") }
        val shift = KeyEvent.KEYCODE_SHIFT_RIGHT
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        keyEventField(RecordingInputConnection(view), fieldId = viewId)
        org.robolectric.shadows.ShadowLog.clear()

        // An eligible restart: the ids of the DOWN, the start as the IME got it, the route.
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))
        restartInputWithNewConnection(RecordingInputConnection(view))
        var log = keyDeliveryLog()
        assertEquals(log.toString(), 4, log.size)
        assertTrue(log[0], log[0].startsWith("keyDelivery: sent DOWN keyCode=$shift repeat=0 through #"))
        assertTrue(log[0], log[0].endsWith(" package=com.example.remote fieldId=$viewId"))
        assertTrue(
            log[1],
            log[1].startsWith("keyDelivery: onStartInput restarting=true package=com.example.remote fieldId=$viewId ")
        )
        assertEquals(
            "keyDelivery: release of keyCode=$shift that went down in package=com.example.remote " +
                "fieldId=$viewId: route=new",
            log[2]
        )
        assertTrue(log[3], log[3].startsWith("keyDelivery: cancelled UP keyCode=$shift downTime=$down through #"))
        assertTrue(log[3], log[3].endsWith(" taken=true"))
        // The connection named in the start is the one the release went through, and not the
        // one the key went down through.
        val newConnection = log[1].substringAfter("connection=")
        val oldConnection = log[0].substringAfter("through ").substringBefore(" ")
        assertTrue(log[3], log[3].contains("through $newConnection (went down through $oldConnection)"))
        assertFalse(newConnection == oldConnection)
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))

        // Kept out: the reason is the condition that failed.
        org.robolectric.shadows.ShadowLog.clear()
        val again = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, again, again)))
        restartInputWithNewConnection(RecordingInputConnection(view), fieldId = viewId + 1)
        log = keyDeliveryLog()
        assertEquals(log.toString(), 4, log.size)
        assertEquals(
            "keyDelivery: release of keyCode=$shift that went down in package=com.example.remote " +
                "fieldId=$viewId: route=old (another field id)",
            log[2]
        )
        val wentDownThrough = log[0].substringAfter("through ").substringBefore(" ")
        assertTrue(log[3], log[3].contains("through $wentDownThrough (went down through $wentDownThrough)"))
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, again, tick())))

        // A finish redirects nothing, and says so.
        org.robolectric.shadows.ShadowLog.clear()
        val third = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, third, third)))
        service.onFinishInput()
        log = keyDeliveryLog()
        assertEquals(log.toString(), 4, log.size)
        assertTrue(log[1], log[1].startsWith("keyDelivery: onFinishInput connection=#"))
        assertTrue(log[2], log[2].endsWith(": route=old (not a restarting start)"))
    }

    @Test
    fun heldShift_restartWhoseConnectionTakesNoKeys_isTriedThereOnce_andNotInTheOldOne() {
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(view), fieldId = viewId)
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))
        first.replacedByTheApp = true

        val second = RecordingInputConnection(view).apply { sendKeyEventResult = false }
        restartInputWithNewConnection(second)
        // One destination is chosen, and a refusal there is not made up for in the other.
        assertEquals(listOf("SHIFT_RIGHT up repeat=0 shift=false"), sentKeys(second))
        assertTrue(second.sentKeyEvents[0].isCanceled)
        assertEquals(listOf("SHIFT_RIGHT down repeat=0 shift=true"), sentKeys(first))
        assertEquals(listOf("SHIFT_RIGHT down repeat=0 shift=true"), keys(view))
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, remapper().heldTargetOf(enterKeyId()))

        // Nothing is owed after the attempt: the next restart and the key's UP send nothing.
        second.sendKeyEventResult = true
        val third = restartInputWithNewConnection(RecordingInputConnection(view))
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        assertEquals(1, first.sentKeyEvents.size)
        assertEquals(1, second.sentKeyEvents.size)
        assertTrue(sentKeys(third).toString(), third.sentKeyEvents.isEmpty())
    }

    @Test
    fun heldShift_restartOverTheConnectionItWentDownThrough_isReleasedThereOnce() {
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        val connection = keyEventField(RecordingInputConnection(view), fieldId = viewId)

        // The connection outlives the start (the IME, or the system, started input over it).
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))
        startInput(inputConnection, restarting = true)
        assertEquals(
            listOf("SHIFT_RIGHT down repeat=0 shift=true", "SHIFT_RIGHT up repeat=0 shift=false"),
            keys(view)
        )
        assertTrue(view[1].isCanceled)
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        assertEquals(2, connection.sentKeyEvents.size)

        // The same without ids, where the release takes the old route to the same place.
        view.clear()
        connection.sentKeyEvents.clear()
        startInput(inputConnection, restarting = false, fieldId = 0)
        val again = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, again, again)))
        startInput(inputConnection, restarting = true)
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, again, tick())))
        assertEquals(
            listOf("SHIFT_RIGHT down repeat=0 shift=true", "SHIFT_RIGHT up repeat=0 shift=false"),
            keys(view)
        )
        assertTrue(view[1].isCanceled)
        assertEquals(2, connection.sentKeyEvents.size)
    }

    @Test
    fun swapOn_enterHeldOverARestartOfItsEditor_keepsTheOldRouteOfItsRelease() {
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(view), fieldId = viewId)

        // Right Shift as Enter: a key that acts. Its release is never sent into the new
        // connection, where a view could act on an Enter UP it saw no DOWN for.
        val shift = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, shift, shift)))
        first.replacedByTheApp = true
        val second = restartInputWithNewConnection(RecordingInputConnection(view))
        assertEquals(listOf("ENTER down repeat=0 shift=false", "ENTER up repeat=0 shift=false"), sentKeys(first))
        assertTrue(first.sentKeyEvents[1].isCanceled)
        assertEquals(listOf("ENTER down repeat=0 shift=false"), keys(view))
        assertTrue(sentKeys(second).toString(), second.sentKeyEvents.isEmpty())
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, shift, tick())))
        assertTrue(sentKeys(second).toString(), second.sentKeyEvents.isEmpty())
    }

    @Test
    fun keyRemappedToSym_heldOverARestartOfItsEditor_keepsTheOldRouteOfItsRelease() {
        remapEnterTo(KeyEvent.KEYCODE_SYM)
        val view = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(view), fieldId = viewId)
        val sym = KeyEvent.KEYCODE_SYM

        val down = tick(1_000L)
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))
        assertEquals(listOf("$sym down repeat=0"), presses(first.sentKeyEvents))
        first.replacedByTheApp = true
        val second = restartInputWithNewConnection(RecordingInputConnection(view))
        assertEquals(listOf("$sym down repeat=0", "$sym up repeat=0 cancelled"), presses(first.sentKeyEvents))
        assertEquals(listOf("$sym down repeat=0"), presses(view))
        assertTrue(presses(second.sentKeyEvents).toString(), second.sentKeyEvents.isEmpty())
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        assertTrue(presses(second.sentKeyEvents).toString(), second.sentKeyEvents.isEmpty())
    }

    @Test
    fun swapOn_bothKeysHeldOverARestartOfTheirEditor_eachIsReleasedOnce_byItsOwnRoute() {
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(view), fieldId = viewId)

        // Right Shift as Enter, then Enter as Shift while it is down (the device reports the
        // physical Shift in the Enter key's meta state).
        val shift = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, shift, shift)))
        val enter = tick()
        assertTrue(
            service.onKeyDown(
                KeyEvent.KEYCODE_ENTER,
                scanKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 28, enter, enter, rightShiftMeta)
            )
        )
        assertEquals(
            listOf("ENTER down repeat=0 shift=false", "SHIFT_RIGHT down repeat=0 shift=true"),
            sentKeys(first)
        )

        first.replacedByTheApp = true
        val second = restartInputWithNewConnection(RecordingInputConnection(view))
        // The Shift ends in the view, through the new connection; the Enter only in the old one.
        assertEquals(listOf("SHIFT_RIGHT up repeat=0 shift=false"), sentKeys(second))
        assertTrue(second.sentKeyEvents[0].isCanceled)
        assertEquals(enter, second.sentKeyEvents[0].downTime)
        assertEquals(
            listOf(
                "ENTER down repeat=0 shift=false",
                "SHIFT_RIGHT down repeat=0 shift=true",
                "ENTER up repeat=0 shift=false"
            ),
            sentKeys(first)
        )
        assertTrue(first.sentKeyEvents[2].isCanceled)
        assertEquals(shift, first.sentKeyEvents[2].downTime)
        assertEquals(
            listOf(
                "ENTER down repeat=0 shift=false",
                "SHIFT_RIGHT down repeat=0 shift=true",
                "SHIFT_RIGHT up repeat=0 shift=false"
            ),
            keys(view)
        )
    }

    @Test
    fun restartOfAnEditor_sendsNothingForAKeyTheImeSentNoDownFor() {
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(view), fieldId = viewId)

        // No key is down.
        val second = restartInputWithNewConnection(RecordingInputConnection(view))
        assertTrue(first.sentKeyEvents.isEmpty())
        assertTrue(second.sentKeyEvents.isEmpty())

        // Right Shift as Enter cannot be sent, so the system delivers the raw Shift: a Shift
        // key the app holds, and not one the IME sent. It is the system's to end.
        second.sendKeyEventResult = false
        val shift = tick()
        assertFalse(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, shift, shift)))
        second.sentKeyEvents.clear()
        second.sendKeyEventResult = true
        val third = restartInputWithNewConnection(RecordingInputConnection(view))
        assertTrue(sentKeys(second).toString(), second.sentKeyEvents.isEmpty())
        assertTrue(sentKeys(third).toString(), third.sentKeyEvents.isEmpty())
        // The restart changed nothing about that: the system delivers the raw UP as well.
        assertFalse(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, shift, tick())))
        assertTrue(sentKeys(third).toString(), third.sentKeyEvents.isEmpty())

        // Enter as Shift cannot be sent, and ends in the IME: the app holds nothing.
        third.sendKeyEventResult = false
        val enter = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, enter, enter)))
        third.sentKeyEvents.clear()
        third.sendKeyEventResult = true
        val fourth = restartInputWithNewConnection(RecordingInputConnection(view))
        assertTrue(sentKeys(third).toString(), third.sentKeyEvents.isEmpty())
        assertTrue(sentKeys(fourth).toString(), fourth.sentKeyEvents.isEmpty())
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, enter, tick())))
        assertTrue(sentKeys(fourth).toString(), fourth.sentKeyEvents.isEmpty())
        assertTrue(keys(view).toString(), view.isEmpty())
    }

    @Test
    fun heldShift_restartIntoAnotherViewWithTheSameIds_isReleasedThere_whichTheIdFilterCannotPrevent() {
        // Two views that report one package and one field id: rows of a list, or two fields of
        // one web view. Focus moves from one to the other under the held key. To the IME this
        // is the same as one view starting its input again.
        enableShiftEnterSwap()
        val viewThatSawTheDown = mutableListOf<KeyEvent>()
        val otherView = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(viewThatSawTheDown), fieldId = viewId)
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))
        first.replacedByTheApp = true
        val second = restartInputWithNewConnection(RecordingInputConnection(otherView))

        // Known limit: the release lands in the view that saw no DOWN, and the view that did
        // keeps the press. It is a cancelled Shift UP, and the only event that goes astray.
        assertEquals(listOf("SHIFT_RIGHT up repeat=0 shift=false"), keys(otherView))
        assertTrue(otherView[0].isCanceled)
        assertEquals(listOf("SHIFT_RIGHT down repeat=0 shift=true"), keys(viewThatSawTheDown))
        assertEquals(1, first.sentKeyEvents.size)
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        assertEquals(1, second.sentKeyEvents.size)
        assertEquals(1, otherView.size)
    }

    @Test
    fun swapOn_enterAsShiftReachesAnAppThatReadsKeys_asAPairedShiftPress() {
        enableShiftEnterSwap()
        val connection = keyEventField()
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        assertEquals(
            listOf("SHIFT_RIGHT down repeat=0 shift=true", "SHIFT_RIGHT up repeat=0 shift=false"),
            sentKeys(connection)
        )
    }

    @Test
    fun swapOn_connectionThatTakesNoKeys_rawShiftGoesToTheSystem_rawEnterGoesNowhere() {
        enableShiftEnterSwap()
        val connection = keyEventField()
        connection.sendKeyEventResult = false

        // Right Shift as Enter cannot be sent: the system delivers the raw pair.
        val shift = tick()
        assertFalse(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, shift, shift)))
        assertFalse(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, shift, tick())))

        // Enter as Shift cannot be sent: a raw Enter would act, so the press ends in the IME.
        val enter = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, enter, enter)))
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, enter, tick())))
    }

    @Test
    fun swapOn_rawShiftTheSystemDelivered_staysARawShift_whenTheConnectionTakesKeysAgain() {
        enableShiftEnterSwap()
        val connection = keyEventField()
        connection.sendKeyEventResult = false
        val down = tick()
        assertFalse(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, down, down)))
        val refused = listOf("ENTER down repeat=0 shift=false")
        assertEquals(refused, sentKeys(connection))

        // The app holds the raw Shift. An Enter repeat or an Enter UP now would belong to no press.
        connection.sendKeyEventResult = true
        assertFalse(
            service.onKeyDown(
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                rightShiftKey(KeyEvent.ACTION_DOWN, down, tick(), repeatCount = 1)
            )
        )
        assertFalse(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, down, tick())))
        assertEquals(refused, sentKeys(connection))

        // The next press is an Enter again.
        val again = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, again, again)))
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, again, tick())))
        assertEquals(
            refused + listOf("ENTER down repeat=0 shift=false", "ENTER up repeat=0 shift=false"),
            sentKeys(connection)
        )
    }

    @Test
    fun swapOn_enterTheAppHolds_neverTurnsIntoARawShift_whenTheConnectionStopsTakingKeys() {
        enableShiftEnterSwap()
        val connection = keyEventField()
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, down, down)))

        // Returning false here would let the system deliver a Shift into the app's Enter press.
        connection.sendKeyEventResult = false
        assertTrue(
            service.onKeyDown(
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                rightShiftKey(KeyEvent.ACTION_DOWN, down, tick(), repeatCount = 1)
            )
        )
        // The connection takes keys again: the press goes on, and ends, as Enter.
        connection.sendKeyEventResult = true
        assertTrue(
            service.onKeyDown(
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                rightShiftKey(KeyEvent.ACTION_DOWN, down, tick(), repeatCount = 2)
            )
        )
        connection.sendKeyEventResult = false
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, down, tick())))
        // Every attempt was an Enter, the refused ones included.
        assertEquals(
            listOf(
                "ENTER down repeat=0 shift=false",
                "ENTER down repeat=1 shift=false",
                "ENTER down repeat=2 shift=false",
                "ENTER up repeat=0 shift=false"
            ),
            sentKeys(connection)
        )
    }

    @Test
    fun swapOn_shiftTheConnectionRefused_staysSwallowed_whenTheConnectionTakesKeysAgain() {
        enableShiftEnterSwap()
        val connection = keyEventField()
        connection.sendKeyEventResult = false
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))
        val refused = listOf("SHIFT_RIGHT down repeat=0 shift=true")
        assertEquals(refused, sentKeys(connection))

        // The app saw no Shift go down: no repeat and no UP of one, and no raw Enter either.
        connection.sendKeyEventResult = true
        assertTrue(
            service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, tick(), repeatCount = 1))
        )
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        assertEquals(refused, sentKeys(connection))
    }

    @Test
    fun swapOn_shiftTheAppHolds_neverTurnsIntoARawEnter_whenTheConnectionStopsTakingKeys() {
        enableShiftEnterSwap()
        val connection = keyEventField()
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))

        connection.sendKeyEventResult = false
        assertTrue(
            service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, tick(), repeatCount = 1))
        )
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        assertEquals(
            listOf(
                "SHIFT_RIGHT down repeat=0 shift=true",
                "SHIFT_RIGHT down repeat=1 shift=true",
                "SHIFT_RIGHT up repeat=0 shift=false"
            ),
            sentKeys(connection)
        )
    }

    @Test
    fun swapOn_upTheImeConsumes_stillEndsTheKeyTheAppHolds() {
        enableShiftEnterSwap()
        val connection = keyEventField()
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, down, down)))

        // The handler takes this Enter UP for itself (the tail of an Alt+Enter).
        setField(service, "consumeAltEnterUntilKeyUp", true)
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, down, tick())))
        assertFalse(getField<Boolean>(service, "consumeAltEnterUntilKeyUp"))
        assertEquals(
            listOf("ENTER down repeat=0 shift=false", "ENTER up repeat=0 shift=false"),
            sentKeys(connection)
        )
    }

    @Test
    fun swapOn_upTheImeConsumes_endsTheRawShiftTheSystemDeliveredInPlaceOfEnter() {
        enableShiftEnterSwap()
        val connection = keyEventField()

        // Right Shift as Enter cannot be sent: the system delivers the raw Shift DOWN.
        connection.sendKeyEventResult = false
        val down = tick()
        assertFalse(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, down, down)))
        connection.sentKeyEvents.clear()

        // The handler takes the UP for itself, as an Enter (the tail of an Alt+Enter): the app
        // would be left holding the Shift. It gets that Shift's UP, cancelled.
        connection.sendKeyEventResult = true
        setField(service, "consumeAltEnterUntilKeyUp", true)
        val up = tick()
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, down, up)))
        assertFalse(getField<Boolean>(service, "consumeAltEnterUntilKeyUp"))
        assertEquals(listOf("SHIFT_RIGHT up repeat=0 shift=false"), sentKeys(connection))
        val release = connection.sentKeyEvents.single()
        assertTrue(release.isCanceled)
        assertEquals(down, release.downTime)
        assertEquals(up, release.eventTime)
        assertEquals(FakeKeyboards.BUILT_IN, release.deviceId)
        assertEquals(54, release.scanCode)

        // The connection still takes no key events at the UP: the system delivers the raw UP,
        // as it delivered the DOWN.
        connection.sentKeyEvents.clear()
        connection.sendKeyEventResult = false
        val again = tick()
        assertFalse(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, again, again)))
        setField(service, "consumeAltEnterUntilKeyUp", true)
        assertFalse(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, again, tick())))
        assertFalse(getField<Boolean>(service, "consumeAltEnterUntilKeyUp"))
    }

    /**
     * Right Shift as Enter cannot be sent, so the system delivers the raw Shift DOWN. [change]
     * is what happens to the editor under the held key; it returns the connection the IME has
     * afterwards, which takes keys. The app still holds the raw Shift, and the system still
     * ends it: the IME leaves the repeat and the UP to the system and sends nothing itself.
     */
    private fun rawShiftTheSystemDeliveredStaysTheSystemsOver(
        change: (first: RecordingInputConnection, view: MutableList<KeyEvent>) -> RecordingInputConnection
    ) {
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(view), fieldId = viewId)
        first.sendKeyEventResult = false
        val down = tick()
        assertFalse(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, down, down)))
        first.sentKeyEvents.clear()
        first.sendKeyEventResult = true

        val now = change(first, view)
        // An Enter repeat or an Enter UP would belong to no press, in this connection too.
        assertFalse(
            service.onKeyDown(
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                rightShiftKey(KeyEvent.ACTION_DOWN, down, tick(), repeatCount = 1)
            )
        )
        // Without its UP from the system the app would hold the Shift from here on.
        assertFalse(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, down, tick())))
        assertTrue(sentKeys(first).toString(), first.sentKeyEvents.isEmpty())
        assertTrue(sentKeys(now).toString(), now.sentKeyEvents.isEmpty())
        assertTrue(keys(view).toString(), view.isEmpty())

        // The next press is an Enter again, through the connection of now.
        val again = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, again, again)))
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, again, tick())))
        assertEquals(listOf("ENTER down repeat=0 shift=false", "ENTER up repeat=0 shift=false"), sentKeys(now))
    }

    @Test
    fun swapOn_rawShiftTheSystemDelivered_heldOverARestartOfItsEditor_getsItsRepeatAndItsUpFromTheSystem() =
        rawShiftTheSystemDeliveredStaysTheSystemsOver { _, view ->
            // The same package and field id, restarting: what a held Shift the IME sent is
            // redirected by. This Shift is not the IME's, and nothing is sent for it.
            restartInputWithNewConnection(RecordingInputConnection(view))
        }

    @Test
    fun swapOn_rawShiftTheSystemDelivered_heldIntoAnotherField_getsItsRepeatAndItsUpFromTheSystem() =
        rawShiftTheSystemDeliveredStaysTheSystemsOver { _, view ->
            restartInputWithNewConnection(RecordingInputConnection(view), fieldId = viewId + 1, restarting = false)
        }

    @Test
    fun swapOn_rawShiftTheSystemDelivered_heldOverAFinish_getsItsRepeatAndItsUpFromTheSystem() =
        rawShiftTheSystemDeliveredStaysTheSystemsOver { first, _ ->
            service.onFinishInput()
            first
        }

    @Test
    fun swapOn_rawShiftTheSystemDelivered_heldOverAFinishAndTwoStarts_getsItsRepeatAndItsUpFromTheSystem() =
        rawShiftTheSystemDeliveredStaysTheSystemsOver { _, view ->
            service.onFinishInput()
            restartInputWithNewConnection(RecordingInputConnection(view), restarting = false)
            restartInputWithNewConnection(RecordingInputConnection(view))
        }

    @Test
    fun swapOn_upTheImeConsumes_ofARawShiftHeldOverAStart_isLeftToTheSystem_andNotSentIntoTheNewConnection() {
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(view), fieldId = viewId)
        first.sendKeyEventResult = false
        val down = tick()
        assertFalse(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, down, down)))
        first.sentKeyEvents.clear()
        val second = restartInputWithNewConnection(RecordingInputConnection(view))
        assertTrue(sentKeys(second).toString(), second.sentKeyEvents.isEmpty())

        // The handler takes the UP for itself, as an Enter (the tail of an Alt+Enter). Within
        // one connection the IME then ends the raw Shift with a cancelled UP through it. This
        // connection saw nothing of the press: the UP is the system's, though it was consumed.
        setField(service, "consumeAltEnterUntilKeyUp", true)
        assertFalse(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, down, tick())))
        assertFalse(getField<Boolean>(service, "consumeAltEnterUntilKeyUp"))
        assertTrue(sentKeys(first).toString(), first.sentKeyEvents.isEmpty())
        assertTrue(sentKeys(second).toString(), second.sentKeyEvents.isEmpty())
        assertTrue(keys(view).toString(), view.isEmpty())
    }

    @Test
    fun swapOn_keyTheSystemDelivered_heldOverARestartUnderAHeldEnterAsShift_stillGetsItsUp() {
        enableShiftEnterSwap()
        val first = keyEventField()

        // Tab goes down as itself (the system delivers it), then Enter as Shift.
        val tab = tick()
        assertFalse(
            service.onKeyDown(KeyEvent.KEYCODE_TAB, scanKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 15, tab, tab))
        )
        val enter = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, enter, enter)))
        val second = restartInputWithNewConnection()
        assertEquals(
            listOf("SHIFT_RIGHT down repeat=0 shift=true", "SHIFT_RIGHT up repeat=0 shift=false"),
            sentKeys(first)
        )

        // The app holds the Tab the system delivered. Its UP is the key's own, with the Shift
        // the held key adds, as any key's is under a held remapped Shift; it was swallowed
        // when a start made the IME forget the Tab.
        assertTrue(
            service.onKeyUp(KeyEvent.KEYCODE_TAB, scanKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 15, tab, tick()))
        )
        assertEquals(listOf("TAB up repeat=0 shift=true"), sentKeys(second))
        assertFalse(second.sentKeyEvents.single().isCanceled)
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, enter, tick())))
        assertEquals(1, second.sentKeyEvents.size)
    }

    @Test
    fun keyNoRemapTouched_heldOverAStart_keepsWhatTheHandlerMakesOfItsUp() {
        // No swap: Enter is Enter, the system delivers it, before a start and after.
        useTitan2EliteBuiltInKeyboard()
        keyEventField()
        val down = tick()
        assertFalse(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))
        val second = restartInputWithNewConnection()
        assertFalse(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))

        // An UP the handler consumes stays consumed: that the app held the key since before
        // the start is no reason to hand it the UP.
        val again = tick()
        assertFalse(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, again, again)))
        val third = restartInputWithNewConnection()
        setField(service, "consumeAltEnterUntilKeyUp", true)
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, again, tick())))
        assertFalse(getField<Boolean>(service, "consumeAltEnterUntilKeyUp"))
        assertTrue(sentKeys(second).toString(), second.sentKeyEvents.isEmpty())
        assertTrue(sentKeys(third).toString(), third.sentKeyEvents.isEmpty())
    }

    /**
     * Right Shift as Enter cannot be sent, so the system delivers the raw Shift DOWN, and the
     * client unbinds under the held key. [afterwards] is what follows before the key's events
     * reach the IME again; it returns the connections made by then. The remapped key has ended
     * at the unbind, as every held remapped key does (its repeat does nothing any more), but
     * the app still holds the raw Shift and only the system can end that: the IME leaves it the
     * UP and sends nothing through any connection. Returns the last of those connections.
     */
    private fun rawShiftTheSystemDeliveredGetsItsUpFromTheSystemAfterAnUnbind(
        afterwards: (view: MutableList<KeyEvent>) -> List<RecordingInputConnection>
    ): RecordingInputConnection? {
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(view), fieldId = viewId)
        first.sendKeyEventResult = false
        val down = tick()
        assertFalse(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, down, down)))
        first.sentKeyEvents.clear()
        first.sendKeyEventResult = true

        service.onUnbindInput()
        val later = afterwards(view)
        assertTrue(
            service.onKeyDown(
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                rightShiftKey(KeyEvent.ACTION_DOWN, down, tick(), repeatCount = 1)
            )
        )
        // Swallowed with the rest of the press, this UP would leave the Shift down in the app.
        assertFalse(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, down, tick())))
        for (connection in later + first) {
            assertTrue(sentKeys(connection).toString(), connection.sentKeyEvents.isEmpty())
        }
        assertTrue(keys(view).toString(), view.isEmpty())
        return later.lastOrNull()
    }

    @Test
    fun swapOn_rawShiftTheSystemDelivered_heldOverAnUnbind_getsItsUpFromTheSystem_whereTheClientBindsAgain() {
        val now = rawShiftTheSystemDeliveredGetsItsUpFromTheSystemAfterAnUnbind { view ->
            listOf(keyEventField(RecordingInputConnection(view), fieldId = viewId))
        }!!

        // The next press is an Enter again, through the connection of now.
        val again = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, again, again)))
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, again, tick())))
        assertEquals(listOf("ENTER down repeat=0 shift=false", "ENTER up repeat=0 shift=false"), sentKeys(now))
    }

    @Test
    fun swapOn_rawShiftTheSystemDelivered_heldOverAnUnbind_getsItsUpFromTheSystem_notThroughTheConnectionLeftBehind() {
        // An unbind is no finish: the connection of before can still be the current one, and it
        // takes keys. A cancelled UP of the raw Shift through it is not the system's UP.
        rawShiftTheSystemDeliveredGetsItsUpFromTheSystemAfterAnUnbind { emptyList() }
    }

    @Test
    fun swapOn_rawShiftTheSystemDelivered_heldOverAnUnbind_getsItsUpFromTheSystem_whenNoConnectionIsLeft() {
        rawShiftTheSystemDeliveredGetsItsUpFromTheSystemAfterAnUnbind {
            setField(service, "mInputConnection", null)
            setField(service, "mStartedInputConnection", null)
            emptyList()
        }
    }

    @Test
    fun swapOn_rawShiftTheSystemDelivered_heldOverAnUnbind_getsItsUpFromTheSystem_alsoAfterARestartOfTheNextEditor() {
        rawShiftTheSystemDeliveredGetsItsUpFromTheSystemAfterAnUnbind { view ->
            val second = keyEventField(RecordingInputConnection(view), fieldId = viewId)
            // The same package and field id, restarting: what a held Shift the IME sent is
            // redirected by. Nothing of this press is the IME's to release.
            listOf(second, restartInputWithNewConnection(RecordingInputConnection(view)))
        }
    }

    @Test
    fun swapOn_keyTheSystemDelivered_heldOverAnUnbind_stillGetsItsUp_underAHeldEnterAsShiftOfTheNextClient() {
        enableShiftEnterSwap()
        keyEventField()
        // Tab goes down as itself (the system delivers it), and the client unbinds under it.
        val tab = tick()
        assertFalse(
            service.onKeyDown(KeyEvent.KEYCODE_TAB, scanKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 15, tab, tab))
        )
        service.onUnbindInput()
        val second = keyEventField()
        val enter = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, enter, enter)))

        // The app holds the Tab: its UP is the key's own, with the Shift the held key adds. It
        // was swallowed when the unbind made the IME forget what the system had delivered.
        assertTrue(
            service.onKeyUp(KeyEvent.KEYCODE_TAB, scanKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 15, tab, tick()))
        )
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, enter, tick())))
        assertEquals(
            listOf(
                "SHIFT_RIGHT down repeat=0 shift=true",
                "TAB up repeat=0 shift=true",
                "SHIFT_RIGHT up repeat=0 shift=false"
            ),
            sentKeys(second)
        )
        assertFalse(second.sentKeyEvents[1].isCanceled)
    }

    @Test
    fun swapOn_keyTheSystemDelivered_heldOverAnUnbind_stillGetsItsUp_underTheRightShiftTheUnbindCutOff() {
        enableShiftEnterSwap()
        val first = keyEventField()
        val tab = tick()
        assertFalse(
            service.onKeyDown(KeyEvent.KEYCODE_TAB, scanKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 15, tab, tab))
        )
        // Right Shift goes down as Enter, sent by the IME; the client unbinds under both keys.
        val shift = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, shift, shift)))
        val sentToFirst = listOf("ENTER down repeat=0 shift=false")
        assertEquals(sentToFirst, sentKeys(first))
        service.onUnbindInput()
        // What the IME sent ends there unreleased: the client it went to is gone.
        assertEquals(sentToFirst, sentKeys(first))
        val second = keyEventField()

        // The framework still reports the held right Shift, which never acted as one: Tab's UP
        // comes without it. Tab was swallowed here too before the delivery outlived the unbind.
        assertTrue(
            service.onKeyUp(
                KeyEvent.KEYCODE_TAB,
                scanKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 15, tab, tick(), metaState = rightShiftMeta)
            )
        )
        assertEquals(listOf("TAB up repeat=0 shift=false"), sentKeys(second))

        // The rest of the cut-off Enter is swallowed as before: the app held no raw key for it.
        assertTrue(
            service.onKeyDown(
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                rightShiftKey(KeyEvent.ACTION_DOWN, shift, tick(), repeatCount = 1)
            )
        )
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, shift, tick())))
        assertEquals(sentToFirst, sentKeys(first))
        assertEquals(1, second.sentKeyEvents.size)
    }

    @Test
    fun swapOn_shiftTheImeSent_heldOverAnUnbind_endsThereUnreleased_andTheRestOfItsPressIsSwallowed() {
        enableShiftEnterSwap()
        val view = mutableListOf<KeyEvent>()
        val first = keyEventField(RecordingInputConnection(view), fieldId = viewId)
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))
        val sent = listOf("SHIFT_RIGHT down repeat=0 shift=true")
        assertEquals(sent, sentKeys(first))

        // Nothing is sent after the client, and nothing is kept to send later: a restarting
        // start of the same package and field id finds no Shift of the IME's to release.
        service.onUnbindInput()
        assertEquals(sent, sentKeys(first))
        val second = restartInputWithNewConnection(RecordingInputConnection(view))
        assertTrue(
            service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, tick(), repeatCount = 1))
        )
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        assertEquals(sent, sentKeys(first))
        assertTrue(sentKeys(second).toString(), second.sentKeyEvents.isEmpty())
        assertEquals(sent, keys(view))
    }

    /**
     * Tab goes down as itself (the system delivers it), the client unbinds under it, and its UP
     * never reaches the IME: the record of the Tab stays. Another client binds, and Enter goes
     * down there as Shift. Returns the connection of that client, which has taken the Shift.
     */
    private fun tabTheSystemDeliveredLosesItsUpAfterAnUnbind_andEnterIsHeldAsShiftInTheNextClient():
        RecordingInputConnection {
        enableShiftEnterSwap()
        keyEventField()
        val tab = tick()
        assertFalse(
            service.onKeyDown(KeyEvent.KEYCODE_TAB, scanKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 15, tab, tab))
        )
        service.onUnbindInput()
        val second = keyEventField()
        val enter = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, enter, enter)))
        assertEquals(listOf("SHIFT_RIGHT down repeat=0 shift=true"), sentKeys(second))
        return second
    }

    @Test
    fun swapOn_keyTheSystemDelivered_whoseUpWasLostAfterAnUnbind_passesTheUpOfALaterPressItSawNoDownOf_once() {
        val second = tabTheSystemDeliveredLosesItsUpAfterAnUnbind_andEnterIsHeldAsShiftInTheNextClient()

        // The UP of another press of Tab (another down time), whose DOWN went elsewhere as
        // well. The record is that of the key, not of a press: this UP is taken for the one
        // it waits for, and goes through the connection with the Shift the held key adds.
        val later = tick()
        assertTrue(
            service.onKeyUp(KeyEvent.KEYCODE_TAB, scanKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 15, later, tick()))
        )
        val sent = listOf("SHIFT_RIGHT down repeat=0 shift=true", "TAB up repeat=0 shift=true")
        assertEquals(sent, sentKeys(second))
        assertFalse(second.sentKeyEvents[1].isCanceled)

        // The record went with it. Without one, such an UP is that of a key the app never saw
        // go down, and is swallowed.
        val last = tick()
        assertTrue(
            service.onKeyUp(KeyEvent.KEYCODE_TAB, scanKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 15, last, tick()))
        )
        assertEquals(sent, sentKeys(second))
    }

    @Test
    fun swapOn_keyTheSystemDelivered_whoseUpWasLostAfterAnUnbind_isPressedAgainAsAFirstPress() {
        val second = tabTheSystemDeliveredLosesItsUpAfterAnUnbind_andEnterIsHeldAsShiftInTheNextClient()

        // A new press replaces the record of the lost one: under the held Shift the Tab is
        // the IME's to send, DOWN and UP, as a Tab pressed for the first time is.
        val again = tick()
        assertTrue(
            service.onKeyDown(KeyEvent.KEYCODE_TAB, scanKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 15, again, again))
        )
        assertTrue(
            service.onKeyUp(KeyEvent.KEYCODE_TAB, scanKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 15, again, tick()))
        )
        val sent = listOf(
            "SHIFT_RIGHT down repeat=0 shift=true",
            "TAB down repeat=0 shift=true",
            "TAB up repeat=0 shift=true"
        )
        assertEquals(sent, sentKeys(second))

        // Nothing of the lost press is left over: one more UP is an orphan.
        val last = tick()
        assertTrue(
            service.onKeyUp(KeyEvent.KEYCODE_TAB, scanKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 15, last, tick()))
        )
        assertEquals(sent, sentKeys(second))
    }

    /**
     * Right Shift as Enter cannot be sent, so the system delivers the raw Shift DOWN; the
     * client unbinds under it, and the key's UP never reaches the IME: the record of the raw
     * Shift stays, and so does the tombstone of the remapped key. Returns the connection of
     * the client that binds next, which takes keys; [view] is what the app has seen through
     * any connection.
     */
    private fun rawShiftTheSystemDeliveredLosesItsUpAfterAnUnbind(
        view: MutableList<KeyEvent>
    ): RecordingInputConnection {
        enableShiftEnterSwap()
        val first = keyEventField(RecordingInputConnection(view), fieldId = viewId)
        first.sendKeyEventResult = false
        val down = tick()
        assertFalse(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, down, down)))
        service.onUnbindInput()
        assertTrue(keys(view).toString(), view.isEmpty())
        return keyEventField(RecordingInputConnection(view), fieldId = viewId)
    }

    @Test
    fun swapOn_rawShiftTheSystemDelivered_whoseUpWasLostAfterAnUnbind_leavesALaterUpToTheSystem_underEnterAsShiftToo() {
        val view = mutableListOf<KeyEvent>()
        val second = rawShiftTheSystemDeliveredLosesItsUpAfterAnUnbind(view)
        val enter = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, enter, enter)))
        val sent = listOf("SHIFT_RIGHT down repeat=0 shift=true")
        assertEquals(sent, sentKeys(second))

        // The UP of another press of right Shift (another down time). Unlike the Tab above,
        // the raw key that stood in for an Enter never goes through a connection, whatever
        // modifier is held by now: the UP is the system's.
        val later = tick()
        assertFalse(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, later, tick())))
        assertEquals(sent, sentKeys(second))
        assertEquals(sent, keys(view))

        // The Shift of the held Enter is the IME's as before.
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, enter, tick())))
        assertEquals(sent + "SHIFT_RIGHT up repeat=0 shift=false", sentKeys(second))
    }

    @Test
    fun swapOn_rawShiftTheSystemDelivered_whoseUpWasLostAfterAnUnbind_isPressedAgainAsEnter() {
        val view = mutableListOf<KeyEvent>()
        val second = rawShiftTheSystemDeliveredLosesItsUpAfterAnUnbind(view)

        // A new press replaces the record of the lost one, and the tombstone with it. The
        // connection of now takes keys, so the key is an Enter again, the IME's from its
        // DOWN to its UP: kept, the old record would hand this press to the system as a Shift.
        val again = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, again, again)))
        assertTrue(
            service.onKeyDown(
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                rightShiftKey(KeyEvent.ACTION_DOWN, again, tick(), repeatCount = 1)
            )
        )
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_UP, again, tick())))
        val sent = listOf(
            "ENTER down repeat=0 shift=false",
            "ENTER down repeat=1 shift=false",
            "ENTER up repeat=0 shift=false"
        )
        assertEquals(sent, sentKeys(second))
        assertEquals(sent, keys(view))
    }

    @Test
    fun upTheImeConsumes_ofAKeyNoRemapTouched_staysConsumed() {
        // The same keyboard and field without the swap: Enter is Enter, the system delivers it.
        useTitan2EliteBuiltInKeyboard()
        val connection = keyEventField()
        val down = tick()
        assertFalse(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down)))

        setField(service, "consumeAltEnterUntilKeyUp", true)
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick())))
        assertFalse(getField<Boolean>(service, "consumeAltEnterUntilKeyUp"))
        assertTrue(sentKeys(connection).toString(), connection.sentKeyEvents.isEmpty())
    }

    @Test
    fun swapOn_keyTheAppHolds_isReleasedAsCancelled_whenItsOwnUpIsLost() {
        enableShiftEnterSwap()
        val connection = keyEventField()

        // Physical Enter went down as Shift and its UP never came: the next press says so.
        val first = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, first, first)))
        val second = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, second, second)))
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, second, tick())))
        assertEquals(
            listOf(
                "SHIFT_RIGHT down repeat=0 shift=true",
                "SHIFT_RIGHT up repeat=0 shift=false",
                "SHIFT_RIGHT down repeat=0 shift=true",
                "SHIFT_RIGHT up repeat=0 shift=false"
            ),
            sentKeys(connection)
        )
        assertEquals(listOf(false, true, false, false), connection.sentKeyEvents.map { it.isCanceled })
        assertEquals(FakeKeyboards.BUILT_IN, connection.sentKeyEvents[1].deviceId)
        assertEquals(28, connection.sentKeyEvents[1].scanCode)
        assertEquals("the lost press's own down time", first, connection.sentKeyEvents[1].downTime)

        // Physical right Shift went down as Enter; a later key no longer carries its Shift bit.
        connection.sentKeyEvents.clear()
        val shift = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, shift, shift)))
        val tab = tick()
        service.onKeyDown(KeyEvent.KEYCODE_TAB, scanKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 15, tab, tab))
        assertEquals(
            listOf("ENTER down repeat=0 shift=false", "ENTER up repeat=0 shift=false"),
            sentKeys(connection)
        )
        assertTrue(connection.sentKeyEvents[1].isCanceled)
        assertEquals(54, connection.sentKeyEvents[1].scanCode)
    }

    @Test
    fun swapOn_keyTheAppHolds_isReleasedAsCancelled_whenItsKeyboardGoesAway() {
        enableShiftEnterSwap()
        val connection = keyEventField()
        val down = tick()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, rightShiftKey(KeyEvent.ACTION_DOWN, down, down)))

        getField<android.hardware.input.InputManager.InputDeviceListener>(service, "inputDeviceListener")
            .onInputDeviceRemoved(FakeKeyboards.BUILT_IN)

        assertEquals(
            listOf("ENTER down repeat=0 shift=false", "ENTER up repeat=0 shift=false"),
            sentKeys(connection)
        )
        assertTrue(connection.sentKeyEvents[1].isCanceled)
    }

    @Test
    fun swapOn_overlappingKeysFilter_treatsEnterAsTheModifierItActsAs() {
        enableSwapInPlainTextField()
        SettingsManager.setOverlappingKeysEnabled(RuntimeEnvironment.getApplication(), true)
        val down = tick()
        service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down))
        assertEquals("A", typeA())
        assertEquals("A", typeA())
        service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick()))
        assertEquals("a", typeA())
    }

    @Test
    fun userKeyRemap_seesTheKeyAfterDeviceNormalization() {
        // The Q25's Ctrl key sends the right Shift key code, and device normalization makes it
        // Ctrl: the user's rules apply to that Ctrl, not to the key code that arrived.
        DeviceSpecific.setBuildFingerprintForTests(
            brand = "zinwa",
            manufacturer = "zinwa",
            model = "Q25",
            device = "Q25",
            product = "q25"
        )
        setField(service, "physicalKeyboardProfileOverride", "auto")
        FakeKeyboards.register()
        assertTrue(
            SettingsManager.setHardwareKeyRemapRules(
                RuntimeEnvironment.getApplication(),
                "q25",
                listOf(
                    KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_SHIFT_RIGHT), KeyEvent.KEYCODE_ENTER),
                    KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_CTRL_LEFT), KeyEvent.KEYCODE_TAB)
                )
            )
        )
        val connection = keyEventField()
        val down = tick()
        assertTrue(
            service.onKeyDown(
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                scanKey(
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, 54, down, down,
                    KeyEvent.META_SHIFT_RIGHT_ON
                )
            )
        )
        assertTrue(
            service.onKeyUp(
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                scanKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_RIGHT, 54, down, tick())
            )
        )
        assertEquals(
            listOf("TAB down repeat=0 shift=false", "TAB up repeat=0 shift=false"),
            sentKeys(connection)
        )
    }

    @Test
    fun keyRemappedToSym_actsAsSym_andAnUnbindLeavesNoPressedStateBehind() {
        useTitan2EliteBuiltInKeyboard()
        val context = RuntimeEnvironment.getApplication()
        assertTrue(
            SettingsManager.setHardwareKeyRemapRules(
                context,
                "titan2elite_qwerty",
                listOf(KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ALT_RIGHT), KeyEvent.KEYCODE_SYM))
            )
        )
        plainTextField()
        val altMeta = KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_ALT_ON
        fun altKey(action: Int, downTime: Long, eventTime: Long) = scanKey(
            action, KeyEvent.KEYCODE_ALT_RIGHT, 100, downTime, eventTime,
            metaState = if (action == KeyEvent.ACTION_UP) 0 else altMeta
        )

        // A tap is a Sym tap: the Sym page opens and Alt never shows.
        val tap = tick()
        service.onKeyDown(KeyEvent.KEYCODE_ALT_RIGHT, altKey(KeyEvent.ACTION_DOWN, tap, tap))
        assertTrue(getField<Boolean>(service, "symPhysicallyPressed"))
        assertFalse(modifierController().altPressed)
        assertFalse(modifierController().altPhysicallyPressed)
        service.onKeyUp(KeyEvent.KEYCODE_ALT_RIGHT, altKey(KeyEvent.ACTION_UP, tap, tap + 40L))
        assertFalse(getField<Boolean>(service, "symPhysicallyPressed"))
        assertTrue(symLayout().isSymActive())
        assertFalse(modifierController().altOneShot)

        // Held when the client unbinds: the pressed state ends, and the late UP toggles nothing.
        val held = tick(1_000L)
        service.onKeyDown(KeyEvent.KEYCODE_ALT_RIGHT, altKey(KeyEvent.ACTION_DOWN, held, held))
        assertTrue(getField<Boolean>(service, "symPhysicallyPressed"))
        service.onUnbindInput()
        assertFalse(getField<Boolean>(service, "symPhysicallyPressed"))
        assertFalse(getField<Boolean>(service, "symTogglePendingOnKeyUp"))
        plainTextField()
        val symActive = symLayout().isSymActive()
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_ALT_RIGHT, altKey(KeyEvent.ACTION_UP, held, tick())))
        assertEquals(symActive, symLayout().isSymActive())
        assertFalse(getField<Boolean>(service, "symPhysicallyPressed"))
        assertFalse(modifierController().altPressed)
    }

    /** The right Alt key (scan 100) of the Titan 2 Elite acts as Sym, in a plain text field. */
    private fun remapRightAltToSym() {
        useTitan2EliteBuiltInKeyboard()
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        assertTrue(
            SettingsManager.setHardwareKeyRemapRules(
                context,
                "titan2elite_qwerty",
                listOf(KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ALT_RIGHT), KeyEvent.KEYCODE_SYM))
            )
        )
        useLayout("qwerty")
        plainTextField()
    }

    private val rightAltMeta = KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_ALT_ON

    private fun rightAltKey(action: Int, downTime: Long, eventTime: Long) = scanKey(
        action, KeyEvent.KEYCODE_ALT_RIGHT, 100, downTime, eventTime,
        metaState = if (action == KeyEvent.ACTION_UP) 0 else rightAltMeta
    )

    @Test
    fun keyRemappedToSym_heldAcrossEditorChanges_keepsTypingSymbols_andItsReleaseIsNoTap() {
        remapRightAltToSym()
        assertEquals("a", typeA())

        val down = tick()
        service.onKeyDown(KeyEvent.KEYCODE_ALT_RIGHT, rightAltKey(KeyEvent.ACTION_DOWN, down, down))
        val symbol = typeA(rightAltMeta)
        assertTrue("held Sym types a symbol, got '$symbol'", symbol.isNotEmpty() && !symbol.equals("a", ignoreCase = true))

        service.onStartInput(editorInfo, true)
        assertEquals("after a restarting start", symbol, typeA(rightAltMeta))

        service.onFinishInput()
        plainTextField()
        assertTrue(getField<Boolean>(service, "symPhysicallyPressed"))
        assertEquals("in the next field", symbol, typeA(rightAltMeta))

        service.onFinishInputView(false)
        service.onWindowHidden()
        assertTrue(getField<Boolean>(service, "symPhysicallyPressed"))
        assertEquals("with the keyboard hidden", symbol, typeA(rightAltMeta))

        assertFalse(symLayout().isSymActive())
        service.onKeyUp(KeyEvent.KEYCODE_ALT_RIGHT, rightAltKey(KeyEvent.ACTION_UP, down, tick()))
        assertFalse(symLayout().isSymActive())
        assertFalse(getField<Boolean>(service, "symPhysicallyPressed"))
        assertFalse(getField<Boolean>(service, "symTogglePendingOnKeyUp"))
        assertFalse(modifierController().altPressed)
        assertEquals("a", typeA())
    }

    @Test
    fun keyRemappedToSym_heldIntoANewEditorAndReleasedUnused_opensNoSymPage() {
        remapRightAltToSym()

        // Without the editor change this press would be a Sym tap.
        val down = tick()
        service.onKeyDown(KeyEvent.KEYCODE_ALT_RIGHT, rightAltKey(KeyEvent.ACTION_DOWN, down, down))
        service.onFinishInput()
        plainTextField()
        assertTrue(getField<Boolean>(service, "symPhysicallyPressed"))
        service.onKeyUp(KeyEvent.KEYCODE_ALT_RIGHT, rightAltKey(KeyEvent.ACTION_UP, down, tick()))
        assertFalse(symLayout().isSymActive())
        assertFalse(getField<Boolean>(service, "symPhysicallyPressed"))

        // A tap in the new editor is one.
        val tap = tick()
        service.onKeyDown(KeyEvent.KEYCODE_ALT_RIGHT, rightAltKey(KeyEvent.ACTION_DOWN, tap, tap))
        service.onKeyUp(KeyEvent.KEYCODE_ALT_RIGHT, rightAltKey(KeyEvent.ACTION_UP, tap, tap + 40L))
        assertTrue(symLayout().isSymActive())
    }

    @Test
    fun keyRemappedToSym_heldIntoAFieldWithoutAnEditor_isNotLeftPressedThere() {
        remapRightAltToSym()
        val down = tick()
        service.onKeyDown(KeyEvent.KEYCODE_ALT_RIGHT, rightAltKey(KeyEvent.ACTION_DOWN, down, down))
        service.onFinishInput()
        keyEventField()
        assertTrue(getField<Boolean>(service, "symPhysicallyPressed"))

        service.onKeyUp(KeyEvent.KEYCODE_ALT_RIGHT, rightAltKey(KeyEvent.ACTION_UP, down, tick()))
        assertFalse(getField<Boolean>(service, "symPhysicallyPressed"))
        assertFalse(getField<Boolean>(service, "symTogglePendingOnKeyUp"))
    }

    @Test
    fun hiddenBarApp_refusesShowRequestsButHardwareTypingAndSymLayerStillWork() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setKeyboardBarHiddenInApp(context, "rocks.pkb.plektra.test", true)
        focusNewField(RecordingInputConnection(), InputType.TYPE_CLASS_TEXT)

        assertFalse(visibilityController().allowsSurface())
        assertFalse(service.onEvaluateInputViewShown())
        assertFalse(service.onShowInputRequested(0, false))
        assertTrue(visibilityController().shouldRecoverSurfaceOnHardwareKey())

        val t0 = 9_000L
        service.onKeyDown(KeyEvent.KEYCODE_B, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, t0, t0))
        service.onKeyUp(KeyEvent.KEYCODE_B, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B, t0, t0 + 20L))
        tapSym(t0 + 100L)
        service.onKeyDown(KeyEvent.KEYCODE_A, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, t0 + 140L, t0 + 140L))
        assertEquals("commits=${recorder.committedTexts}", listOf("b", "@"), recorder.committedTexts)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(visibilityController().allowsSurface())
    }

    @Test
    fun hiddenBarApp_dedicatedEmojiKeyAndPickerRequestsDoNotOpenInvisiblePickers() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setKeyboardBarHiddenInApp(context, "rocks.pkb.plektra.test", true)
        focusNewField(RecordingInputConnection(), InputType.TYPE_CLASS_TEXT)
        setField(service, "physicalKeyboardProfileOverride", "mp01")

        assertTrue(service.onKeyDown(666, keyEvent(KeyEvent.ACTION_DOWN, 666, 9_500L, 9_500L)))
        assertEquals(0, symLayout().currentSymPage())
        assertFalse(symLayout().openClipboardPage())
        assertEquals(0, symLayout().currentSymPage())
        recorder.committedTexts.clear()
        service.onKeyDown(KeyEvent.KEYCODE_C, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 9_600L, 9_600L))
        assertEquals(listOf("c"), recorder.committedTexts)
    }

    @Test
    fun hiddenBarApp_appSwitchAndSettingsChangeReevaluateTheBlock() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setKeyboardBarHiddenInApp(context, "com.example.blocked", true)

        focusNewField(RecordingInputConnection(), InputType.TYPE_CLASS_TEXT, packageName = "com.example.blocked")
        assertFalse(visibilityController().allowsSurface())
        focusNewField(RecordingInputConnection(), InputType.TYPE_CLASS_TEXT, packageName = "com.example.allowed")
        assertTrue(visibilityController().allowsSurface())
        assertTrue(symLayout().openEmojiPickerPage())

        // Adding the current app while a picker is open closes the picker and blocks the bar.
        SettingsManager.setKeyboardBarHiddenInApp(context, "com.example.allowed", true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(visibilityController().allowsSurface())
        assertEquals(0, symLayout().currentSymPage())
        assertFalse(service.onShowInputRequested(0, false))

        SettingsManager.setKeyboardBarHiddenInApp(context, "com.example.allowed", false)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(visibilityController().allowsSurface())
        focusNewField(RecordingInputConnection(), InputType.TYPE_CLASS_TEXT, packageName = "com.example.blocked")
        assertFalse(visibilityController().allowsSurface())
    }

    private fun visibilityController(): KeyboardVisibilityController =
        getField(service, "keyboardVisibilityController")

    /** The Enter key (scan 28) of the Titan 2 Elite acts as [target], in a plain text field. */
    private fun remapEnterTo(target: Int) {
        useTitan2EliteBuiltInKeyboard()
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        assertTrue(
            SettingsManager.setHardwareKeyRemapRules(
                context,
                "titan2elite_qwerty",
                listOf(KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ENTER), target))
            )
        )
        useLayout("qwerty")
        plainTextField()
    }

    /** Pressed and physically pressed, as the IME keeps it for Ctrl or Alt. */
    private fun heldAs(target: Int): Pair<Boolean, Boolean> = modifierController().let {
        if (target == KeyEvent.KEYCODE_CTRL_LEFT) {
            it.ctrlPressed to it.ctrlPhysicallyPressed
        } else {
            it.altPressed to it.altPhysicallyPressed
        }
    }

    /** One-shot or latch, as a tap or a double tap of Ctrl or Alt leaves it. */
    private fun armed(target: Int): Boolean = modifierController().let {
        if (target == KeyEvent.KEYCODE_CTRL_LEFT) {
            it.ctrlOneShot || it.ctrlLatchActive
        } else {
            it.altOneShot || it.altLatchActive
        }
    }

    private fun heldRemappedModifierStaysPressedThroughEditorChanges(target: Int) {
        remapEnterTo(target)
        assertEquals("a", typeA())
        val down = tick()
        service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down))
        assertEquals(true to true, heldAs(target))

        service.onStartInput(editorInfo, true)
        assertEquals("after a restarting start", true to true, heldAs(target))

        // A new editor resets the modifiers; the key is still down as this one, and no tap.
        service.onFinishInput()
        plainTextField()
        assertEquals("in the next field", true to true, heldAs(target))
        assertFalse("in the next field", armed(target))

        service.onFinishInputView(false)
        service.onWindowHidden()
        assertEquals("with the keyboard hidden", true to true, heldAs(target))
        assertFalse("with the keyboard hidden", armed(target))

        // Its repeat is no new press and its release no tap: nothing is left pressed or armed.
        service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, tick(), repeatCount = 1))
        assertFalse("after a repeat", armed(target))
        service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick()))
        assertEquals(false to false, heldAs(target))
        assertFalse("after the release", armed(target))
        assertEquals("a", typeA())
        assertTrue(recorder.editorActions.isEmpty())
    }

    private fun heldRemappedModifierIsNotLeftPressedWithoutAnEditor(target: Int) {
        remapEnterTo(target)
        val down = tick()
        service.onKeyDown(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_DOWN, down, down))

        // Focus moves to a view that reads keys itself, where the IME releases no modifiers.
        service.onFinishInput()
        keyEventField()
        assertEquals(true to true, heldAs(target))
        service.onKeyUp(KeyEvent.KEYCODE_ENTER, enterKey(KeyEvent.ACTION_UP, down, tick()))
        assertEquals(false to false, heldAs(target))

        service.onFinishInput()
        plainTextField()
        assertFalse(armed(target))
        assertEquals("a", typeA())
    }

    @Test
    fun keyRemappedToCtrl_heldAcrossEditorChanges_staysPressed_andItsReleaseIsNoTap() =
        heldRemappedModifierStaysPressedThroughEditorChanges(KeyEvent.KEYCODE_CTRL_LEFT)

    @Test
    fun keyRemappedToAlt_heldAcrossEditorChanges_staysPressed_andItsReleaseIsNoTap() =
        heldRemappedModifierStaysPressedThroughEditorChanges(KeyEvent.KEYCODE_ALT_LEFT)

    @Test
    fun keyRemappedToCtrl_heldIntoAFieldWithoutAnEditor_isNotLeftPressedThere() =
        heldRemappedModifierIsNotLeftPressedWithoutAnEditor(KeyEvent.KEYCODE_CTRL_LEFT)

    @Test
    fun keyRemappedToAlt_heldIntoAFieldWithoutAnEditor_isNotLeftPressedThere() =
        heldRemappedModifierIsNotLeftPressedWithoutAnEditor(KeyEvent.KEYCODE_ALT_LEFT)

    private fun symLayout(): rocks.pkb.plektra.core.SymLayoutController =
        getField(service, "symLayoutController")

    private fun focusNewField(
        newRecorder: RecordingInputConnection,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        packageName: String = "rocks.pkb.plektra.test",
        fieldId: Int = 0
    ) {
        recorder = newRecorder
        inputConnection = recorder.asProxy()
        editorInfo = EditorInfo().apply {
            this.inputType = inputType
            this.packageName = packageName
            this.fieldId = fieldId
        }
        setField(service, "mInputConnection", inputConnection)
        setField(service, "mStartedInputConnection", inputConnection)
        setField(service, "mInputEditorInfo", editorInfo)
        service.onStartInput(editorInfo, false)
    }

    private fun configureAppEnterOverride(packageName: String, strategy: String) {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setAppEnterBehaviorEnabled(context, true)
        SettingsManager.setAppEnterBehaviorOverrides(
            context,
            listOf(
                SettingsManager.AppEnterBehaviorOverride(
                    packageName = packageName,
                    behavior = SettingsManager.ENTER_BEHAVIOR_ENTER_SEND_SHIFT_NEWLINE,
                    sendStrategy = strategy
                )
            )
        )
        focusNewField(
            newRecorder = RecordingInputConnection(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            packageName = packageName
        )
    }

    private fun keyEvent(
        action: Int,
        keyCode: Int,
        downTime: Long,
        eventTime: Long,
        metaState: Int = 0,
        repeatCount: Int = 0
    ): KeyEvent {
        return KeyEvent(downTime, eventTime, action, keyCode, repeatCount, metaState)
    }

    private fun modifierController(): ModifierStateController {
        return getField(service, "modifierStateController")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(target: Any, fieldName: String): T {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target) as T
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        error("Field not found: $fieldName")
    }

    private fun setField(target: Any, fieldName: String, value: Any?) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        error("Field not found: $fieldName")
    }

    @Test
    fun blackberryCurrencyKeyCommitsConfiguredCurrencyOnceThroughRealServiceRouting() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "Q25")
        SettingsManager.setPhysicalKeyboardCurrencySymbol(context, "£")
        setField(service, "physicalKeyboardProfileOverride", "Q25")
        recorder.committedTexts.clear()
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_GRAVE, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_GRAVE)))
        assertEquals(listOf("£"), recorder.committedTexts)
    }

    /**
     * [view] stands for the view this connection's key events reach, when a test needs two
     * connections that lead to one view: it gets the events the connection passed on.
     */
    private class RecordingInputConnection(val view: MutableList<KeyEvent>? = null) {
        /**
         * The app has replaced this connection: the IME can still send into it, the framework
         * on the app's side drops what arrives (and the IME is told nothing).
         */
        var replacedByTheApp = false
        var commitAttempts = 0
        var commitResult = true
        var textBeforeCursor: String = ""
        var sendKeyEventResult: Boolean = true
        var performEditorActionResult: Boolean = false
        val committedTexts = mutableListOf<String>()
        val sentKeyEvents = mutableListOf<KeyEvent>()
        val editorActions = mutableListOf<Int>()
        val contextMenuActions = mutableListOf<Int>()
        val deleteSurroundingTextCalls = mutableListOf<Pair<Int, Int>>()

        fun asProxy(): InputConnection {
            return Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java)
            ) { _, method, args ->
                if (method.name == "commitText") commitAttempts++
                when (method.name) {
                    "commitText" -> if (!commitResult) false else {
                        val text = args?.getOrNull(0)?.toString()
                        if (text != null) {
                            committedTexts += text
                            textBeforeCursor += text
                        }
                        true
                    }
                    "deleteSurroundingText" -> {
                        val before = (args?.getOrNull(0) as? Int) ?: 0
                        val after = (args?.getOrNull(1) as? Int) ?: 0
                        deleteSurroundingTextCalls += before to after
                        if (before > 0 && textBeforeCursor.isNotEmpty()) {
                            val keep = (textBeforeCursor.length - before).coerceAtLeast(0)
                            textBeforeCursor = textBeforeCursor.take(keep)
                        }
                        true
                    }
                    "sendKeyEvent" -> {
                        val event = args?.getOrNull(0) as? KeyEvent
                        if (event != null) {
                            sentKeyEvents += event
                            if (sendKeyEventResult && !replacedByTheApp) view?.add(event)
                        }
                        sendKeyEventResult
                    }
                    "performEditorAction" -> {
                        val action = args?.getOrNull(0) as? Int
                        if (action != null) editorActions += action
                        performEditorActionResult
                    }
                    "performContextMenuAction" -> {
                        val id = args?.getOrNull(0) as? Int
                        if (id != null) contextMenuActions += id
                        true
                    }
                    "getTextBeforeCursor" -> {
                        val count = (args?.getOrNull(0) as? Int) ?: textBeforeCursor.length
                        textBeforeCursor.takeLast(count)
                    }
                    "getExtractedText" -> ExtractedText().apply {
                        text = textBeforeCursor
                        selectionStart = textBeforeCursor.length
                        selectionEnd = textBeforeCursor.length
                    }
                    "beginBatchEdit", "endBatchEdit", "finishComposingText" -> true
                    else -> defaultValue(method.returnType)
                }
            } as InputConnection
        }

        private fun defaultValue(type: Class<*>): Any? {
            return when {
                type == Boolean::class.javaPrimitiveType -> false
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                type == Float::class.javaPrimitiveType -> 0f
                type == Double::class.javaPrimitiveType -> 0.0
                type == Short::class.javaPrimitiveType -> 0.toShort()
                type == Byte::class.javaPrimitiveType -> 0.toByte()
                type == Char::class.javaPrimitiveType -> 0.toChar()
                else -> null
            }
        }
    }

    @Test
    fun longPress_realBoundaryCallbackRejectedCommit_doesNotReportAlternate() {
        useLayout("uyghur")
        SettingsManager.setLongPressModifier(service, "alt")
        SettingsManager.setLongPressThreshold(service, 50L)
        SettingsManager.setFrenchPunctuationSpacing(service, true)
        SettingsManager.setFrenchPunctuationOnlyFrenchLayouts(service, false)
        recorder.textBeforeCursor = "ب"
        val manager = getField<AlternateCharacterManager>(service, "alternateCharacterManager")
        manager.addAltKeyMapping(KeyEvent.KEYCODE_L, "?")
        val alternates = mutableListOf<Char>()
        manager.onAltCharInserted = { alternates += it }
        manager.handleKeyWithAltMapping(KeyEvent.KEYCODE_L,
            KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L, 0, KeyEvent.META_SHIFT_ON), false, inputConnection)
        assertEquals("بلا", recorder.textBeforeCursor)
        val attemptsBeforeReplacement = recorder.commitAttempts
        recorder.commitResult = false
        shadowOf(android.os.Looper.getMainLooper()).idleFor(60, java.util.concurrent.TimeUnit.MILLISECONDS)
        manager.handleKeyUp(KeyEvent.KEYCODE_L, false)
        assertEquals("ب", recorder.textBeforeCursor)
        assertTrue("Real service boundary callback reported rejected alternate: $alternates", alternates.isEmpty())
        assertEquals("only the handled replacement commit is attempted",
            attemptsBeforeReplacement + 1, recorder.commitAttempts)
    }


    @Test
    fun longPress_deferredSpaceMustNotSwallowOpeningBracket() {
        useLayout("uyghur")
        SettingsManager.setLongPressModifier(service, "alt")
        SettingsManager.setLongPressThreshold(service, 50L)
        SettingsManager.setFrenchPunctuationSpacing(service, false)
        SettingsManager.setAutoCorrectEnabled(service, false)
        SettingsManager.setSpaceAfterPunctuation(service, ".")
        rocks.pkb.plektra.core.DeferredPunctuationSpaceTracker.clear()
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
        recorder.textBeforeCursor = "ب"
        val manager = getField<AlternateCharacterManager>(service, "alternateCharacterManager")
        manager.addAltKeyMapping(KeyEvent.KEYCODE_PERIOD, "(")
        val alternates = mutableListOf<Char>()
        manager.onAltCharInserted = { alternates += it }
        service.onKeyDown(KeyEvent.KEYCODE_PERIOD, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PERIOD))
        assertEquals("ب.", recorder.textBeforeCursor)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(60, java.util.concurrent.TimeUnit.MILLISECONDS)
        service.onKeyUp(KeyEvent.KEYCODE_PERIOD, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PERIOD))
        assertEquals("ب (", recorder.textBeforeCursor)
        assertEquals(listOf('('), alternates)
    }


    @Test
    fun emojiSearchHeldInSearchIsConsumed() {
        val picker = openEmojiPickerSearch()
        val modifier = modifierController()
        modifier.clearShiftState(resetPressedState = true)
        val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 10000L, 10000L, meta))
        pressKey(KeyEvent.KEYCODE_A, 10050L, meta)
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 10000L, 10450L))
        assertEquals("A", searchText(picker))
        pressKey(KeyEvent.KEYCODE_B, 10500L)
        assertEquals("Ab", searchText(picker))
    }

    @Test
    fun emojiSearchSoftOneShotInSearchIsConsumed() {
        val picker = openEmojiPickerSearch()
        val modifier = modifierController()
        modifier.clearShiftState(resetPressedState = true)
        modifier.shiftOneShot = true
        pressKey(KeyEvent.KEYCODE_A, 10000L)
        assertEquals("A", searchText(picker))
        pressKey(KeyEvent.KEYCODE_B, 10100L)
        assertEquals("Ab", searchText(picker))
    }

    @Test
    fun emojiSearchOnUyghur_softOneShotTypesTheWholeMultiCharacterLetter() {
        useLayout("uyghur")
        val picker = openEmojiPickerSearch()
        val modifier = modifierController()
        modifier.clearShiftState(resetPressedState = true)
        modifier.shiftOneShot = true
        pressKey(KeyEvent.KEYCODE_L, 10000L)
        assertEquals("لا", searchText(picker))
        assertFalse(modifier.shiftOneShot)
        pressKey(KeyEvent.KEYCODE_L, 10100L)
        assertEquals("لال", searchText(picker))
    }

    @Test
    fun emojiSearchManualLatchSurvivesSearch() {
        val picker = openEmojiPickerSearch()
        val modifier = modifierController()
        modifier.clearShiftState(resetPressedState = true)
        modifier.capsLockEnabled = true
        pressKey(KeyEvent.KEYCODE_A,10000L)
        pressKey(KeyEvent.KEYCODE_B,10100L)
        assertTrue(modifier.capsLockEnabled)
    }

    private fun useCalculatorInput() {
        editorInfo.packageName = "com.google.android.calculator"
        editorInfo.inputType = EditorInfo.TYPE_NULL
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
        modifierController().clearAltState(resetPressedState = true)
        modifierController().altTapLatches = false
        setField(service, "isInputViewActive", false)
        setField(service, "requestedInputViewShown", false)
        getField<AlternateCharacterManager>(service, "alternateCharacterManager")
            .addAltKeyMapping(KeyEvent.KEYCODE_W, "1")
    }

    @Test
    fun calculatorAltLockSendsRepeatedDigitsAndConsumesOriginalKeyUps() {
        useCalculatorInput()
        pressKey(KeyEvent.KEYCODE_ALT_LEFT, 10000L)
        pressKey(KeyEvent.KEYCODE_ALT_LEFT, 10100L)
        assertTrue(modifierController().altLatchActive)
        pressKey(KeyEvent.KEYCODE_W, 10200L)
        pressKey(KeyEvent.KEYCODE_W, 10300L)
        assertEquals(listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_1),
            recorder.sentKeyEvents.map { it.keyCode })
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP),
            recorder.sentKeyEvents.map { it.action })
        assertTrue(modifierController().altLatchActive)
        pressKey(KeyEvent.KEYCODE_ALT_LEFT, 10400L)
        assertFalse(modifierController().altLatchActive)
    }

    @Test
    fun calculatorHeldAltDigitsTakePriorityOverLauncherShortcuts() {
        useCalculatorInput()
        SettingsManager.setQuickLauncherAltShortcutsOutsideTextFields(service, true)
        val meta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 10000L, 10000L, meta))
        pressKey(KeyEvent.KEYCODE_W, 10100L, meta)
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 10000L, 10200L))
        assertEquals(2, recorder.sentKeyEvents.size)
        assertTrue(recorder.sentKeyEvents.all { it.keyCode == KeyEvent.KEYCODE_1 && it.metaState == 0 })
        assertFalse(modifierController().altOneShot)
        assertFalse(modifierController().altPressed)
        assertEquals(null, shadowOf(service).nextStartedActivity)
    }

    @Test
    fun otherTypeNullAppsKeepTheirExistingAltRouting() {
        useCalculatorInput()
        editorInfo.packageName = "com.example.other"
        SettingsManager.setQuickLauncherAltShortcutsOutsideTextFields(service, false)
        pressKey(KeyEvent.KEYCODE_ALT_LEFT, 10000L)
        pressKey(KeyEvent.KEYCODE_ALT_LEFT, 10100L)
        pressKey(KeyEvent.KEYCODE_W, 10200L)
        assertTrue(recorder.sentKeyEvents.isEmpty())
        assertFalse(modifierController().altLatchActive)
    }

    @Test
    fun calculatorOperatorsUseTheSameMappingsWithHeldAndLockedAlt() {
        val mappings = linkedMapOf(
            KeyEvent.KEYCODE_A to ("*" to KeyEvent.KEYCODE_NUMPAD_MULTIPLY),
            KeyEvent.KEYCODE_G to ("/" to KeyEvent.KEYCODE_NUMPAD_DIVIDE),
            KeyEvent.KEYCODE_O to ("+" to KeyEvent.KEYCODE_NUMPAD_ADD),
            KeyEvent.KEYCODE_I to ("-" to KeyEvent.KEYCODE_NUMPAD_SUBTRACT),
            KeyEvent.KEYCODE_T to ("(" to KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN),
            KeyEvent.KEYCODE_Y to (")" to KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN),
            KeyEvent.KEYCODE_M to ("." to KeyEvent.KEYCODE_PERIOD),
            KeyEvent.KEYCODE_N to ("," to KeyEvent.KEYCODE_NUMPAD_COMMA)
        )
        for (locked in listOf(false, true)) {
            useCalculatorInput()
            SettingsManager.setQuickLauncherAltShortcutsOutsideTextFields(service, true)
            val manager = getField<AlternateCharacterManager>(service, "alternateCharacterManager")
            mappings.forEach { (key, mapping) -> manager.addAltKeyMapping(key, mapping.first) }
            recorder.sentKeyEvents.clear()
            val meta = if (locked) 0 else KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            if (locked) {
                pressKey(KeyEvent.KEYCODE_ALT_LEFT, 20000L)
                pressKey(KeyEvent.KEYCODE_ALT_LEFT, 20100L)
            } else {
                service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT,
                    keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 20000L, 20000L, meta))
            }
            mappings.keys.forEachIndexed { i, key ->
                val time = 20200L + i * 100
                assertTrue(service.onKeyDown(key, keyEvent(KeyEvent.ACTION_DOWN, key, time, time, meta)))
                // Firmware can report a different modifier state when the character key is released.
                assertTrue(service.onKeyUp(key, keyEvent(KeyEvent.ACTION_UP, key, time, time + 30, 0)))
            }
            val expected = mappings.values.flatMap { listOf(it.second, it.second) }
            assertEquals(expected, recorder.sentKeyEvents.map { it.keyCode })
            assertTrue(recorder.sentKeyEvents.all { it.metaState == 0 })
            assertEquals(0, recorder.commitAttempts)
            assertFalse(getField<Boolean>(service, "isInputViewActive"))
            assertFalse(getField<Boolean>(service, "requestedInputViewShown"))
            assertEquals(null, shadowOf(service).nextStartedActivity)
            if (!locked) {
                service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT,
                    keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 20000L, 21500L))
                assertFalse(modifierController().altOneShot)
            }
        }
    }

    @Test
    fun calculatorOneShotRepeatsTheResolvedCharacterUntilKeyUp() {
        useCalculatorInput()
        pressKey(KeyEvent.KEYCODE_ALT_LEFT, 30000L)
        service.onKeyDown(KeyEvent.KEYCODE_W,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, 30100L, 30100L))
        assertFalse(modifierController().altOneShot)
        val repeat = KeyEvent(30100L, 30200L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, 1)
        assertTrue(service.onKeyDown(KeyEvent.KEYCODE_W, repeat))
        assertTrue(service.onKeyUp(KeyEvent.KEYCODE_W,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_W, 30100L, 30300L)))
        assertEquals(List(4) { KeyEvent.KEYCODE_1 }, recorder.sentKeyEvents.map { it.keyCode })
    }

    @Test
    fun calculatorUnmappedAltDoesNotOpenShortcutAssignment() {
        useCalculatorInput()
        SettingsManager.setQuickLauncherAltShortcutsOutsideTextFields(service, true)
        getField<AlternateCharacterManager>(service, "alternateCharacterManager").removeAltKeyMapping(KeyEvent.KEYCODE_A)
        pressKey(KeyEvent.KEYCODE_ALT_LEFT, 30000L)
        pressKey(KeyEvent.KEYCODE_A, 30100L)
        assertEquals(null, shadowOf(service).nextStartedActivity)
    }

}
