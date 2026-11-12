import Foundation
import CoreLocation

/// Existing authorized fixes contribute one coarse region; no GPS request or server coordinates.
enum MessagingRegionHint {
 private final class State: @unchecked Sendable {
  let lock=NSLock();var region:String?;var expires=Date.distantPast
 }
 private static let state=State()
 static func observe(_ location: CLLocation) {
  let age=Date().timeIntervalSince(location.timestamp)
  guard (0...1800).contains(age), (0...100_000).contains(location.horizontalAccuracy),
        let region=nearest(latitude:location.coordinate.latitude,longitude:location.coordinate.longitude) else { return }
  state.lock.withLock {state.region=region;state.expires=Date().addingTimeInterval(1800)}
 }
 static func invalidate() {state.lock.withLock {state.region=nil;state.expires = .distantPast}}
 static func preferredRegion() -> String {
  if let region=state.lock.withLock({Date()<state.expires ? state.region : nil}) {return region}
  return timeZoneRegion(TimeZone.current.identifier)
 }
 static func nearest(latitude:Double,longitude:Double) -> String? {
  guard latitude.isFinite,longitude.isFinite,(-90...90).contains(latitude),(-180...180).contains(longitude) else {return nil}
  func score(_ lat:Double,_ lon:Double)->Double {
   let a=latitude * .pi/180,b=lat * .pi/180
   return sin(a)*sin(b)+cos(a)*cos(b)*cos((longitude-lon) * .pi/180)
  }
  return score(41.2,-95.9)>score(52.4,5.5) ? RegionalRoutePolicy.us : RegionalRoutePolicy.europe
 }
 static func timeZoneRegion(_ zone:String)->String {
  zone.hasPrefix("America/") || zone.hasPrefix("Pacific/") ||
   (zone.hasPrefix("Australia/") && zone != "Australia/Perth") ? RegionalRoutePolicy.us : RegionalRoutePolicy.europe
 }
}
