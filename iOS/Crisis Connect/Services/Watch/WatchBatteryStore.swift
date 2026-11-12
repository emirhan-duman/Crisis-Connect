import Foundation
import Combine

@MainActor
final class WatchBatteryStore: ObservableObject {
    static let shared = WatchBatteryStore()
    private let percentKey = "watch_battery_percent"
    private let chargingKey = "watch_battery_charging"
    private let timestampKey = "watch_battery_timestamp"

    @Published var percent: Int?
    @Published var isCharging: Bool = false

    init() {
        refresh()
    }

    func refresh() {
        let time = UserDefaults.standard.double(forKey: timestampKey)
        if time > 0 && Date().timeIntervalSince1970 - time < 86400 {
            let p = UserDefaults.standard.integer(forKey: percentKey)
            if p >= 0 && p <= 100 {
                percent = p
                isCharging = UserDefaults.standard.bool(forKey: chargingKey)
                return
            }
        }
        percent = nil
        isCharging = false
    }

    func recordBattery(percent: Int, isCharging: Bool = false) {
        guard percent >= 0 && percent <= 100 else { return }
        self.percent = percent
        self.isCharging = isCharging
        UserDefaults.standard.set(percent, forKey: percentKey)
        UserDefaults.standard.set(isCharging, forKey: chargingKey)
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: timestampKey)
    }
}
