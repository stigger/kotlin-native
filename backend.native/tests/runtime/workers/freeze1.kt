/*
 * Copyright 2010-2018 JetBrains s.r.o.
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

package runtime.workers.freeze1

import kotlin.test.*

import konan.worker.*

data class Node(var previous: Node?, var data: Int)

fun makeCycle(count: Int): Node {
    val first = Node(null, 0)
    var current = first
    for (index in 1 .. count - 1) {
        current = Node(current, index)
    }
    first.previous = current
    return first
}

@Test fun runTest() {
    try {
        makeCycle(10).freeze()
    } catch (e: FreezingException) {
        println("OK, cannot freeze cyclic")
    }

    val immutable = Node(null, 4).freeze()
    try {
        immutable.data = 42
    } catch (e: InvalidMutabilityException) {
        println("OK, cannot mutate frozen")
    }
}