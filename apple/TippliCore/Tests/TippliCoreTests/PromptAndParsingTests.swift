import XCTest
@testable import TippliCore

final class PromptAndParsingTests: XCTestCase {
    override func setUp() {
        super.setUp()
        _ = testResources
    }

    private var builder: PromptBuilder { PromptBuilder(resources: testResources) }

    func testFillIsSinglePassAndLiteral() {
        let out = PromptBuilder.fill("a {{x}} b {{y}} c {{unknown}} {{", ["x": "{{y}}", "y": "Y"])
        XCTAssertEqual(out, "a {{y}} b Y c {{unknown}} {{")
    }

    func testCorrectionPrompt() {
        let lang = testResources.language("de-CH")!
        let r = builder.correction(language: lang, keyboardId: "qwertz", strength: "light", context: "Vorher. ", segment: "ich bin hir ")
        XCTAssertEqual(r.task, .correction)
        XCTAssertTrue(r.system.contains("Language: " + lang.promptName))
        XCTAssertTrue(r.system.contains(lang.rules))
        XCTAssertTrue(r.system.contains("Keyboard: QWERTZ (Deutsch/Schweiz, ä ö ü): qwertzuiopü / asdfghjklöä / yxcvbnm"))
        XCTAssertTrue(r.system.contains(testResources.promptParts.strengthRules["light"]!))
        XCTAssertFalse(r.system.contains("{{"))
        XCTAssertEqual(r.user, "<context>Vorher. </context>\n<segment>ich bin hir </segment>")
    }

    func testCorrectionPromptUsesLayoutOverride() {
        let lang = testResources.language("en")!
        let r = builder.correction(language: lang, keyboardId: "qwertz", strength: "normal", context: "", segment: "x")
        XCTAssertTrue(r.system.contains("Keyboard: QWERTZ"))
        let r2 = builder.correction(language: lang, keyboardId: lang.keyboard, strength: "normal", context: "", segment: "x")
        XCTAssertTrue(r2.system.contains("Keyboard: QWERTY: qwertyuiop / asdfghjkl / zxcvbnm"))
    }

    func testConnectionTestPrompt() {
        let lang = testResources.language("de-CH")!
        let r = builder.connectionTest(language: lang, keyboardId: "qwertz", strength: "normal")
        XCTAssertEqual(r.task, .test)
        XCTAssertTrue(r.user.contains("<context></context>"))
        XCTAssertTrue(r.user.contains(testResources.promptParts.testSegment))
    }

    func testTipsPrompt() {
        let lang = testResources.language("de-CH")!
        var sections = TipsSections()
        sections.writing = false
        sections.emotion = false
        let r = builder.tips(text: "Hallo zäme", language: lang, enabledVariants: ["formal", "shorter"], sections: sections,
                             history: [CorrectionRecord(raw: "halo", corrected: "Hallo"), CorrectionRecord(raw: "zame", corrected: "zäme")])
        XCTAssertEqual(r.task, .tips)
        XCTAssertTrue(r.system.contains("formal (\"Formeller\": more formal and polite), shorter (\"Kürzer\": shorter and to the point)"))
        XCTAssertTrue(r.system.contains("return them as empty lists (or level 0 with empty strings): tips, emotion."))
        XCTAssertTrue(r.system.contains(testResources.promptParts.uiLanguage))
        XCTAssertFalse(r.system.contains("{{"))
        XCTAssertTrue(r.user.contains("<text>Hallo zäme</text>"))
        XCTAssertTrue(r.user.contains("halo -> Hallo\nzame -> zäme"))

        let none = builder.tips(text: "x", language: lang, enabledVariants: [], sections: TipsSections(), history: [])
        XCTAssertTrue(none.user.contains("(none)"))
        XCTAssertTrue(none.system.contains("empty strings): none."))
    }

    func testMoodAndTranslatePrompts() {
        let mood = builder.mood(paragraph: "SO NICHT!!!")
        XCTAssertEqual(mood.task, .mood)
        XCTAssertEqual(mood.user, "SO NICHT!!!")
        XCTAssertTrue(mood.system.contains(testResources.promptParts.uiLanguage))
        let target = testResources.languages.translateTargets.first { $0.id == "en" }!
        let tr = builder.translate(text: "Hoi", target: target)
        XCTAssertEqual(tr.task, .translate)
        XCTAssertTrue(tr.system.hasPrefix("Translate the user's text into English."))
        XCTAssertEqual(tr.user, "Hoi")
    }

    func testParseTipsWithCodeFence() throws {
        let text = """
        Here you go:
        ```json
        {"rewrites":[{"id":"formal","label":"Formeller","text":"Guten Tag"},{"id":"x","label":"","text":""}],
         "explanations":[{"from":"halo","to":"Hallo","why":"Tippfehler"}],
         "tips":["Kürzer schreiben", ""],
         "emotion":{"level":"2","label":"Gereizt","reason":"Viele Ausrufezeichen","calmer":"Ruhig bitte"}}
        ```
        """
        let t = try ResponseParsing.parseTips(text)
        XCTAssertEqual(t.rewrites, [TipsRewrite(id: "formal", label: "Formeller", text: "Guten Tag")])
        XCTAssertEqual(t.explanations, [TipsExplanation(from: "halo", to: "Hallo", why: "Tippfehler")])
        XCTAssertEqual(t.tips, ["Kürzer schreiben"])
        XCTAssertEqual(t.emotion, TipsEmotion(level: 2, label: "Gereizt", reason: "Viele Ausrufezeichen", calmer: "Ruhig bitte"))
    }

    func testParseTipsMissingFieldsDefault() throws {
        let t = try ResponseParsing.parseTips("{}")
        XCTAssertEqual(t, TipsResult())
    }

    func testParseMood() throws {
        XCTAssertEqual(try ResponseParsing.parseMood("{\"level\": 3, \"reason\": \"Wut\"}"), MoodResult(level: 3, reason: "Wut"))
        XCTAssertEqual(try ResponseParsing.parseMood("Sure: {\"level\": 9}"), MoodResult(level: 3, reason: ""))
        XCTAssertThrowsError(try ResponseParsing.parseMood("no json")) { error in
            XCTAssertEqual(AIError.from(error).kind, .badResponse)
        }
    }

    func testStringsLookup() {
        let s = testResources.strings
        XCTAssertEqual(s["toolbar.undo"], "Original")
        XCTAssertEqual(s["settings.triggerLive"], "Live – Wort für Wort (empfohlen)")
        XCTAssertEqual(s.list("tips.emotionLevels").count, 4)
        XCTAssertEqual(s["does.not.exist"], "does.not.exist")
        XCTAssertEqual(AIError(.rateLimited).message, s["errors.rateLimited"])
    }

    func testSettingsDefaultsAndValidation() {
        let d = makeTestDefaults()
        let store = SettingsStore(defaults: d, resources: testResources)
        XCTAssertEqual(store.trigger, "live")
        XCTAssertEqual(store.engineSettings().trigger, .live)
        XCTAssertEqual(store.int(SettingKey.pauseMs), 700)
        XCTAssertEqual(store.keyboardMode, .hex)
        XCTAssertEqual(store.activeLanguage.id, "de-CH")
        d.set("bogus", forKey: SettingKey.trigger)
        XCTAssertEqual(store.trigger, "live")
        d.set(50, forKey: SettingKey.pauseMs)
        XCTAssertEqual(store.int(SettingKey.pauseMs), 300)
        d.set(["en", "xx", "de-CH"], forKey: SettingKey.languages)
        XCTAssertEqual(store.enabledLanguages.map { $0.id }, ["en", "de-CH"])
        XCTAssertEqual(store.activeLanguage.id, "de-CH")
        d.set("en", forKey: SettingKey.activeLanguage)
        XCTAssertEqual(store.keyboardId(for: store.activeLanguage), "qwerty")
        d.set("qwertz", forKey: SettingKey.keyboard("en"))
        XCTAssertEqual(store.keyboardId(for: store.activeLanguage), "qwertz")
        store.noteEmojiUsed("😀")
        store.noteEmojiUsed("👍")
        store.noteEmojiUsed("😀")
        XCTAssertEqual(store.emojiRecents, ["😀", "👍"])
        XCTAssertEqual(store.allowedValues(SettingKey.trigger), ["live", "pause", "word", "sentence", "manual"])
    }

    func testProviderShortNames() {
        XCTAssertEqual(testResources.provider("anthropic")?.shortName, "Claude")
        XCTAssertEqual(testResources.provider("openai")?.shortName, "ChatGPT")
        XCTAssertEqual(testResources.provider("github")?.shortName, "Copilot")
    }

    func testThemePalette() {
        let light = ThemePalette.make(dark: false, resources: testResources)
        XCTAssertEqual(light.accent, RGBA(hex: "#4F46E5"))
        XCTAssertEqual(RGBA(hex: "#0000002E")?.a ?? 0, 46.0 / 255.0, accuracy: 0.0001)
        XCTAssertNil(RGBA(hex: "#12"))
        XCTAssertTrue(ThemePalette.isDark(setting: "system", systemIsDark: true))
        XCTAssertFalse(ThemePalette.isDark(setting: "light", systemIsDark: true))
    }
}
