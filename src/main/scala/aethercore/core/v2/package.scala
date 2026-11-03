package aethercore.core

/** Source-compatible v2 facade. All architectural meanings have ONE definition
  * in core.semantic. ProductCore imports semantic directly, never this facade.
  * These are aliases, not duplicate classes or duplicate enum hardware.
  */
package object v2 {
  type DecodedInstruction = aethercore.core.semantic.DecodedInstruction
  type DecodedMemoryOperation = aethercore.core.semantic.DecodedMemoryOperation
  type DecodedControlFlow = aethercore.core.semantic.DecodedControlFlow
  type DecodedSystemOperation = aethercore.core.semantic.DecodedSystemOperation
  type PendingPrivilegedEffect = aethercore.core.semantic.PendingPrivilegedEffect

  val OperandSourceKind = aethercore.core.semantic.OperandSourceKind
  val ControlFlowKind = aethercore.core.semantic.ControlFlowKind
  val MemoryOperationKind = aethercore.core.semantic.MemoryOperationKind
  val SystemOperationKind = aethercore.core.semantic.SystemOperationKind
  val OrderingClass = aethercore.core.semantic.OrderingClass
}
