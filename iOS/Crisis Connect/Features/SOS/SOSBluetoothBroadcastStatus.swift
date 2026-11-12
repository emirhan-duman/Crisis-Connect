import CoreBluetooth

enum SOSBluetoothBroadcastStatus: Equatable {
    case broadcasting, preparing, poweredOff, unauthorized, unsupported, unavailable

    init(isBroadcasting: Bool, bluetoothState: CBManagerState) {
        switch bluetoothState {
        case .poweredOff: self = .poweredOff
        case .unauthorized: self = .unauthorized
        case .unsupported: self = .unsupported
        case .unknown, .resetting: self = .preparing
        case .poweredOn: self = isBroadcasting ? .broadcasting : .unavailable
        @unknown default: self = .unavailable
        }
    }

    var isBroadcasting: Bool { self == .broadcasting }

    var titleKey: String {
        switch self {
        case .broadcasting: return "SOS_BROADCAST_ACTIVE"
        case .preparing: return "SOS_BLUETOOTH_PREPARING"
        case .poweredOff: return "SOS_BLUETOOTH_OFF"
        case .unauthorized: return "SOS_BLUETOOTH_PERMISSION"
        case .unsupported: return "SOS_BLUETOOTH_UNSUPPORTED"
        case .unavailable: return "SOS_BLUETOOTH_UNAVAILABLE"
        }
    }

    var detailKey: String {
        switch self {
        case .broadcasting: return "SOS_BROADCAST_HINT"
        case .poweredOff: return "SOS_BLUETOOTH_OFF_HELP"
        case .unauthorized: return "SOS_BLUETOOTH_PERMISSION_HELP"
        default: return "SOS_BLUETOOTH_UNAVAILABLE_HELP"
        }
    }
}
