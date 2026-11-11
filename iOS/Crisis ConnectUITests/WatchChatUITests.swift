import XCTest
final class WatchChatUITests: XCTestCase {
 @MainActor func testLifecycleBubblesAfterColdReopen() throws {
  continueAfterFailure=false
  let app=XCUIApplication()
  app.launchEnvironment=["CRISIS_WATCH_E2E":"1","CRISIS_WATCH_CHAT_TEST":"1"]
  app.launchArguments=["-AppleLanguages","(tr)","-AppleLocale","tr_TR"]
  var acceptedCount=0
  for phase in 0..<2 {
   app.launch()
   XCTAssertTrue(app.staticTexts["Yaralıyım"].waitForExistence(timeout:20))
   XCTAssertEqual(app.staticTexts.matching(identifier:"watchReplyStatus_uncertain").count,1)
   XCTAssertEqual(app.staticTexts.matching(identifier:"watchReplyStatus_noPeer").count,1)
   let count=app.staticTexts.matching(identifier:"watchReplyStatus_accepted").count
   if phase == 0 {acceptedCount=count;XCTAssertTrue((1...2).contains(count))}
   else {XCTAssertEqual(count,acceptedCount)}
   XCTAssertEqual(app.staticTexts.matching(NSPredicate(format:"label == %@ OR label == %@","İyiyim","I am safe")).count,acceptedCount)
   let image=XCTAttachment(screenshot:app.screenshot());image.name="Saat yaşam döngüsü sohbeti "+String(phase);image.lifetime = .keepAlways;add(image)
   app.terminate()
  }
 }
 @MainActor func testNormalBubblesAndColdReopen() throws {
  continueAfterFailure=false
  let app=XCUIApplication()
  app.launchEnvironment=["CRISIS_WATCH_E2E":"1","CRISIS_WATCH_CHAT_TEST":"1"]
  app.launchArguments=["-AppleLanguages","(tr)","-AppleLocale","tr_TR"]
  for phase in 0..<2 {
   app.launch()
   XCTAssertTrue(app.staticTexts["İyiyim"].waitForExistence(timeout:20))
   XCTAssertTrue(app.staticTexts["Yaralıyım"].exists)
   XCTAssertTrue(app.staticTexts["Yardıma ihtiyacım var"].exists)
   XCTAssertEqual(app.staticTexts.matching(identifier:"watchReplyStatus_accepted").count,1)
   XCTAssertEqual(app.staticTexts.matching(identifier:"watchReplyStatus_uncertain").count,1)
   XCTAssertEqual(app.staticTexts.matching(identifier:"watchReplyStatus_noPeer").count,1)
   let image=XCTAttachment(screenshot:app.screenshot());image.name="Saat sohbeti "+String(phase);image.lifetime = .keepAlways;add(image)
   app.terminate()
  }
 }
}
