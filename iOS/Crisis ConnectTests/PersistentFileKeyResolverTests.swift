import XCTest
import Security
@testable import Crisis_Connect
final class PersistentFileKeyResolverTests: XCTestCase {
 func testUnavailableKeychainCannotReplaceExistingKey() {
  var inserted=false
  XCTAssertNil(PersistentFileKeyResolver.resolve(read:{(errSecInteractionNotAllowed,nil)},insert:{_ in inserted=true;return errSecSuccess},generate:{Data(repeating:1,count:32)}))
  XCTAssertFalse(inserted)
 }
 func testUnpersistedKeyCannotEncryptFiles() {
  XCTAssertNil(PersistentFileKeyResolver.resolve(read:{(errSecItemNotFound,nil)},insert:{_ in errSecNotAvailable},generate:{Data(repeating:1,count:32)}))
 }
 func testConcurrentCreationUsesThePersistedWinner() {
  var calls=0;let winner=Data(repeating:2,count:32).base64EncodedString()
  let result=PersistentFileKeyResolver.resolve(read:{calls+=1;return calls==1 ? (errSecItemNotFound,nil):(errSecSuccess,Data(winner.utf8))},insert:{_ in errSecDuplicateItem},generate:{Data(repeating:1,count:32)})
  XCTAssertEqual(result,winner)
 }
 func testCorruptExistingKeyCannotBeSilentlyRotated() {
  var inserted=false
  XCTAssertNil(PersistentFileKeyResolver.resolve(read:{(errSecSuccess,Data("corrupt".utf8))},insert:{_ in inserted=true;return errSecSuccess},generate:{Data(repeating:1,count:32)}));XCTAssertFalse(inserted)
 }
}
