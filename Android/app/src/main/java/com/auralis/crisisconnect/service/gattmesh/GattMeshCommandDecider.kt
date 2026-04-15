package com.auralis.crisisconnect.service.gattmesh

internal enum class GattMeshRuntimeCommand {
    START,
    STOP,
    RECONCILE
}

internal enum class GattMeshRuntimeDecision {
    STOP_SERVICE,
    START_RUNTIME,
    RECONCILE_RUNTIME,
    NO_OP
}

internal object GattMeshCommandDecider {
    fun decide(
        command: GattMeshRuntimeCommand,
        settingsEnabled: Boolean,
        runtimeActive: Boolean
    ): GattMeshRuntimeDecision {
        return when (command) {
            GattMeshRuntimeCommand.STOP -> GattMeshRuntimeDecision.STOP_SERVICE
            GattMeshRuntimeCommand.RECONCILE -> {
                if (!settingsEnabled) {
                    GattMeshRuntimeDecision.STOP_SERVICE
                } else {
                    GattMeshRuntimeDecision.RECONCILE_RUNTIME
                }
            }

            GattMeshRuntimeCommand.START -> {
                if (!settingsEnabled) {
                    GattMeshRuntimeDecision.STOP_SERVICE
                } else if (!runtimeActive) {
                    GattMeshRuntimeDecision.START_RUNTIME
                } else {
                    GattMeshRuntimeDecision.NO_OP
                }
            }
        }
    }
}
