//
//  Item.swift
//  Crisis Connect
//
//  Created by Emirhan Duman on 25.10.2025.
//

import Foundation
import SwiftData

@Model
final class Item {
    var timestamp: Date
    
    init(timestamp: Date) {
        self.timestamp = timestamp
    }
}
