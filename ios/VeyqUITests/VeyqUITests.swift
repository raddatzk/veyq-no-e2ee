import XCTest

final class VeyqUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testConnectAndOpenMemoryDetail() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing"]
        app.launch()

        let field = app.textFields["instance-url-field"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("https://memories.example.test")

        app.buttons["connect-button"].tap()

        let homeTitle = app.staticTexts["memory-home-title"]
        XCTAssertTrue(homeTitle.waitForExistence(timeout: 5))
        XCTAssertEqual(homeTitle.label, "Memories")
        XCTAssertFalse(app.buttons["bottom-tab-memories"].exists)
        XCTAssertFalse(app.buttons["bottom-tab-uploads"].exists)
        XCTAssertTrue(app.buttons["upload-status-button"].exists)
        XCTAssertTrue(app.buttons["bottom-search-button"].exists)

        app.buttons["settings-button"].tap()
        XCTAssertTrue(app.staticTexts["Settings"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Appearance"].exists)
        XCTAssertTrue(app.staticTexts["Language"].exists)
        app.buttons["Dark"].tap()
        app.buttons["German"].tap()
        XCTAssertTrue(app.staticTexts["Einstellungen"].waitForExistence(timeout: 5))
        app.buttons["Fertig"].tap()

        app.buttons["memory-space-button"].tap()
        XCTAssertTrue(app.staticTexts["Kontext"].waitForExistence(timeout: 5))
        app.buttons["context-scope-row"].tap()
        app.buttons["space-option-group-family"].tap()
        XCTAssertTrue(app.buttons["memory-card-birthday-weekend"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["memory-card-summer-south-tyrol"].exists)

        app.buttons["memory-card-birthday-weekend"].tap()
        XCTAssertTrue(app.staticTexts["memory-detail-title"].waitForExistence(timeout: 5))
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

        app.buttons["upload-status-button"].tap()
        let uploadQueueTitle = app.staticTexts["upload-queue-title"]
        XCTAssertTrue(uploadQueueTitle.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["upload-state-failed"].exists)
        app.buttons["Fertig"].tap()

        app.buttons["bottom-search-button"].tap()
        let searchTitle = app.staticTexts["search-sheet-title"]
        XCTAssertTrue(searchTitle.waitForExistence(timeout: 5))

        let searchField = app.textFields["search-query-field"]
        XCTAssertTrue(searchField.exists)
        searchField.tap()
        searchField.typeText("garden")

        XCTAssertTrue(app.staticTexts["search-result-morning-garden"].waitForExistence(timeout: 5))
    }
}
