/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.konan.lower

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.*
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.TypeUtils

internal class LateinitLowering(
        val context: CommonBackendContext,
        private val generateParameterNameInAssertion: Boolean = false
) : FileLoweringPass {

    private val KOTLIN_FQ_NAME                  = FqName("kotlin")
    private val kotlinPackageScope              = context.ir.irModule.descriptor.getPackage(KOTLIN_FQ_NAME).memberScope
    private val isInitializedPropertyDescriptor = kotlinPackageScope
            .getContributedVariables(Name.identifier("isInitialized"), NoLookupLocation.FROM_BACKEND).single {
                it.extensionReceiverParameter.let { it != null && TypeUtils.getClassDescriptor(it.type) == context.reflectionTypes.kProperty0 }
            }
    private val isInitializedGetterDescriptor   = isInitializedPropertyDescriptor.getter!!

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(object : IrBuildingTransformer(context) {

            override fun visitVariable(declaration: IrVariable): IrStatement {
                declaration.transformChildrenVoid(this)

                val descriptor = declaration.descriptor
                if (!descriptor.isLateInit) return declaration

                assert(declaration.initializer == null, { "'lateinit' modifier is not allowed for variables with initializer" })
                assert(!KotlinBuiltIns.isPrimitiveType(descriptor.type), { "'lateinit' modifier is not allowed on primitive types" })
                builder.at(declaration).run {
                    declaration.initializer = irNull()
                }
                return declaration
            }

            override fun visitGetValue(expression: IrGetValue): IrExpression {
                val symbol = expression.symbol
                val descriptor = symbol.descriptor as? VariableDescriptor
                if (descriptor == null || !descriptor.isLateInit) return expression

                assert(!KotlinBuiltIns.isPrimitiveType(descriptor.type), { "'lateinit' modifier is not allowed on primitive types" })
                builder.at(expression).run {
                    return irBlock(expression) {
                        // TODO: do data flow analysis to check if value is proved to be not-null.
                        +irIfThen(
                                irEqualsNull(irGet(symbol)),
                                throwUninitializedPropertyAccessException(symbol)
                        )
                        +irGet(symbol)
                    }
                }
            }

            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)

                val descriptor = expression.descriptor
                if (descriptor != isInitializedGetterDescriptor) return expression

                val propertyReference = expression.extensionReceiver!! as IrPropertyReference
                assert(propertyReference.extensionReceiver == null, { "'lateinit' modifier is not allowed on extension properties" })
                // TODO: Take propertyReference.fieldSymbol as soon as it will show up in IR.
                val propertyDescriptor = propertyReference.descriptor

                val type = propertyDescriptor.type
                assert(!KotlinBuiltIns.isPrimitiveType(type), { "'lateinit' modifier is not allowed on primitive types" })
                builder.at(expression).run {
                    @Suppress("DEPRECATION")
                    val fieldValue = IrGetFieldImpl(
                            expression.startOffset,
                            expression.endOffset,
                            propertyDescriptor,
                            propertyReference.dispatchReceiver
                    )
                    return irNotEquals(fieldValue, irNull())
                }
            }

            override fun visitProperty(declaration: IrProperty): IrStatement {
                declaration.transformChildrenVoid(this)

                if (!declaration.descriptor.isLateInit || !declaration.descriptor.kind.isReal)
                    return declaration

                val backingField = declaration.backingField!!
                transformGetter(backingField.symbol, declaration.getter!!)

                assert(backingField.initializer == null, { "'lateinit' modifier is not allowed for properties with initializer" })
                val irBuilder = context.createIrBuilder(backingField.symbol, declaration.startOffset, declaration.endOffset)
                irBuilder.run {
                    backingField.initializer = irExprBody(irNull())
                }

                return declaration
            }

            private fun transformGetter(backingFieldSymbol: IrFieldSymbol, getter: IrFunction) {
                val type = backingFieldSymbol.descriptor.type
                assert(!KotlinBuiltIns.isPrimitiveType(type), { "'lateinit' modifier is not allowed on primitive types" })
                val irBuilder = context.createIrBuilder(getter.symbol, getter.startOffset, getter.endOffset)
                irBuilder.run {
                    getter.body = irBlockBody {
                        val resultVar = irTemporary(
                                irGetField(getter.dispatchReceiverParameter?.let { irGet(it.symbol) }, backingFieldSymbol)
                        )
                        +irIfThenElse(
                                context.builtIns.nothingType,
                                irNotEquals(irGet(resultVar.symbol), irNull()),
                                irReturn(irGet(resultVar.symbol)),
                                throwUninitializedPropertyAccessException(backingFieldSymbol)
                        )
                    }
                }
            }
        })
    }

    private fun IrBuilderWithScope.throwUninitializedPropertyAccessException(backingFieldSymbol: IrSymbol) =
            irCall(throwErrorFunction).apply {
                if (generateParameterNameInAssertion) {
                    putValueArgument(
                            0,
                            IrConstImpl.string(
                                    startOffset,
                                    endOffset,
                                    context.builtIns.stringType,
                                    backingFieldSymbol.descriptor.name.asString()
                            )
                    )
                }
            }

    private val throwErrorFunction = context.ir.symbols.ThrowUninitializedPropertyAccessException

}