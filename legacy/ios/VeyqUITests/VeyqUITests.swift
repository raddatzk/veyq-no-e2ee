import XCTest

final class VeyqUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testConnectAndOpenMemoryDetail() throws {
        let app = XCUIApplication()
        let tenantTabTitle = "Family Memories"
        app.launchArguments = ["--ui-testing"]
        app.launch()

        let field = app.textFields["instance-url-field"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("https://memories.example.test")

        app.buttons["connect-button"].tap()

        let recentTabTitle = "Recent"
        let recentHomeTitle = app.staticTexts["recent-home-title"]
        XCTAssertTrue(recentHomeTitle.waitForExistence(timeout: 5))
        XCTAssertEqual(recentHomeTitle.label, tenantTabTitle)
        XCTAssertTrue(app.staticTexts[recentTabTitle].exists)
        XCTAssertTrue(app.searchFields["Search Memories"].exists)
        XCTAssertTrue(app.buttons["recent-context-button"].exists)
        XCTAssertTrue(app.tabBars.buttons[recentTabTitle].exists)
        XCTAssertLessThan(app.tabBars.buttons[recentTabTitle].frame.midX, app.tabBars.buttons[tenantTabTitle].frame.midX)
        XCTAssertLessThan(app.tabBars.buttons[recentTabTitle].frame.midX, app.tabBars.buttons["Groups"].frame.midX)

        app.tabBars.buttons[tenantTabTitle].tap()
        let homeTitle = app.staticTexts["memory-home-title"]
        XCTAssertTrue(homeTitle.waitForExistence(timeout: 5))
        XCTAssertEqual(homeTitle.label, tenantTabTitle)
        XCTAssertTrue(app.tabBars.buttons[tenantTabTitle].exists)
        XCTAssertTrue(app.tabBars.buttons["Groups"].exists)
        XCTAssertTrue(app.tabBars.buttons["Personal"].exists)
        XCTAssertTrue(app.tabBars.buttons[recentTabTitle].exists)
        XCTAssertTrue(tabBarButton(in: app, startingWith: "Activity").exists)
        XCTAssertFalse(app.buttons["upload-status-button"].exists)
        XCTAssertTrue(app.searchFields["Search Memories"].exists)

        app.buttons["memory-context-button"].tap()
        XCTAssertTrue(app.staticTexts["Context"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Memory Scope"].exists)
        XCTAssertTrue(app.buttons["tenant-option-family"].exists)
        XCTAssertTrue(app.buttons["context-edit-accounts-button"].exists)
        app.buttons["context-edit-accounts-button"].tap()
        XCTAssertTrue(app.staticTexts["Accounts"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["accounts-add-account-button"].exists)
        XCTAssertTrue(app.staticTexts["Administrator"].exists)
        XCTAssertTrue(app.staticTexts[tenantTabTitle].exists)
        app.buttons["Done"].tap()
        XCTAssertTrue(app.staticTexts["Accounts"].waitForNonExistence(timeout: 5))

        let rootFolder = app.buttons["folder-row-folder-travel"]
        XCTAssertTrue(rootFolder.waitForExistence(timeout: 5))
        rootFolder.tap()
        let folderUpButton = app.buttons["folder-up-toolbar-button"]
        XCTAssertTrue(folderUpButton.waitForExistence(timeout: 5))
        XCTAssertFalse(app.tabBars.buttons[tenantTabTitle].exists)
        XCTAssertFalse(app.tabBars.buttons["Groups"].exists)
        XCTAssertFalse(app.tabBars.buttons["Personal"].exists)
        XCTAssertFalse(tabBarButton(in: app, startingWith: "Activity").exists)
        folderUpButton.tap()
        XCTAssertTrue(folderUpButton.waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.tabBars.buttons[tenantTabTitle].exists)
        XCTAssertTrue(app.tabBars.buttons["Groups"].exists)
        XCTAssertTrue(app.tabBars.buttons["Personal"].exists)
        XCTAssertTrue(tabBarButton(in: app, startingWith: "Activity").exists)

        app.buttons["settings-button"].tap()
        XCTAssertTrue(app.staticTexts["Settings"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Appearance"].exists)
        XCTAssertTrue(app.staticTexts["Language"].exists)
        app.buttons["settings-theme-picker"].tap()
        app.buttons["Dark"].tap()
        app.buttons["settings-language-picker"].tap()
        app.buttons["German"].tap()
        XCTAssertTrue(app.staticTexts["Einstellungen"].waitForExistence(timeout: 5))
        app.buttons["Fertig"].tap()

        let groupScopeButton = app.tabBars.buttons["Gruppen"]
        XCTAssertTrue(groupScopeButton.waitForExistence(timeout: 5))
        XCTAssertTrue(tabBarButton(in: app, startingWith: "Aktivität").exists)
        groupScopeButton.tap()
        XCTAssertTrue(app.buttons["create-group-button"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["create-memory-button"].exists)
        XCTAssertFalse(app.buttons["memory-card-summer-south-tyrol"].exists)
        XCTAssertFalse(app.buttons["memory-card-birthday-weekend"].exists)
        app.buttons["create-group-button"].tap()
        let groupTitleField = app.textFields["group-title-field"]
        XCTAssertTrue(groupTitleField.waitForExistence(timeout: 5))
        groupTitleField.tap()
        groupTitleField.typeText("Road Crew")
        app.buttons["group-save-button"].tap()
        XCTAssertTrue(groupTitleField.waitForNonExistence(timeout: 5))

        let groupOption = app.buttons["group-space-card-group-family"]
        XCTAssertTrue(groupOption.waitForExistence(timeout: 5))
        groupOption.tap()
        XCTAssertFalse(app.buttons["scope-up-toolbar-button"].exists)
        XCTAssertTrue(app.buttons["memory-card-birthday-weekend"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["memory-card-summer-south-tyrol"].exists)

        groupScopeButton.tap()
        XCTAssertTrue(groupOption.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["create-memory-button"].exists)
        XCTAssertFalse(app.buttons["memory-card-summer-south-tyrol"].exists)
        XCTAssertFalse(app.buttons["memory-card-birthday-weekend"].exists)
        groupOption.tap()
        XCTAssertFalse(app.buttons["scope-up-toolbar-button"].exists)
        XCTAssertTrue(app.buttons["memory-card-birthday-weekend"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["memory-card-summer-south-tyrol"].exists)

        app.buttons["memory-card-birthday-weekend"].tap()
        XCTAssertTrue(app.staticTexts["memory-detail-title"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.tabBars.buttons[tenantTabTitle].exists)
        XCTAssertFalse(app.tabBars.buttons["Gruppen"].exists)
        XCTAssertFalse(app.tabBars.buttons["Persönlich"].exists)
        XCTAssertFalse(tabBarButton(in: app, startingWith: "Aktivität").exists)
        XCTAssertTrue(app.buttons["memory-all-media-button"].exists)
        XCTAssertTrue(app.buttons["memory-read-journal-button"].exists)

        app.buttons["memory-all-media-button"].tap()
        XCTAssertTrue(app.scrollViews["memory-full-gallery"].waitForExistence(timeout: 5))
        let galleryTile = app.buttons["memory-gallery-tile-media-family-table"]
        XCTAssertTrue(galleryTile.waitForExistence(timeout: 5))
        galleryTile.tap()
        XCTAssertTrue(app.buttons["memory-gallery-close-button"].waitForExistence(timeout: 5))
        app.buttons["memory-gallery-close-button"].tap()
        XCTAssertTrue(app.buttons["memory-gallery-close-button"].waitForNonExistence(timeout: 5))

        galleryTile.tap()
        let galleryViewer = app.otherElements["memory-gallery-viewer"]
        XCTAssertTrue(galleryViewer.waitForExistence(timeout: 5))
        let dragStart = galleryViewer.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35))
        let dragEnd = galleryViewer.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.85))
        dragStart.press(forDuration: 0.05, thenDragTo: dragEnd)
        XCTAssertTrue(app.buttons["memory-gallery-close-button"].waitForNonExistence(timeout: 5))

        app.navigationBars.buttons.firstMatch.tap()

        app.buttons["memory-read-journal-button"].tap()
        XCTAssertTrue(app.scrollViews["memory-journal-reader"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()

        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.staticTexts["memory-home-title"].waitForExistence(timeout: 5))
        tabBarButton(in: app, startingWith: "Aktivität").tap()
        let uploadQueueTitle = app.staticTexts["upload-queue-title"]
        XCTAssertTrue(uploadQueueTitle.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["upload-state-failed"].exists)
        app.tabBars.buttons[tenantTabTitle].tap()

        let searchField = app.searchFields["Memories suchen"]
        XCTAssertTrue(searchField.waitForExistence(timeout: 5))
        searchField.tap()
        searchField.typeText("garden")

        let searchResult = app.buttons["search-result-morning-garden"]
        XCTAssertTrue(searchResult.waitForExistence(timeout: 5))
        searchResult.tap()

        XCTAssertTrue(app.staticTexts["memory-detail-title"].waitForExistence(timeout: 5))
    }

    private func tabBarButton(in app: XCUIApplication, startingWith labelPrefix: String) -> XCUIElement {
        app.tabBars.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", labelPrefix)).firstMatch
    }
}
