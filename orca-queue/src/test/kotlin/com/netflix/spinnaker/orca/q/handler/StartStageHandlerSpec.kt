/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.q.handler

import com.natpryce.hamkrest.allElements
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.hasElement
import com.natpryce.hamkrest.isEmpty
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spinnaker.orca.ExecutionStatus.*
import com.netflix.spinnaker.orca.events.StageStarted
import com.netflix.spinnaker.orca.pipeline.RestrictExecutionDuringTimeWindow
import com.netflix.spinnaker.orca.pipeline.model.Pipeline
import com.netflix.spinnaker.orca.pipeline.model.Stage
import com.netflix.spinnaker.orca.pipeline.model.SyntheticStageOwner.STAGE_BEFORE
import com.netflix.spinnaker.orca.pipeline.model.Task
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionNotFoundException
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.pipeline.util.ContextParameterProcessor
import com.netflix.spinnaker.orca.q.*
import com.netflix.spinnaker.orca.time.fixedClock
import com.nhaarman.mockito_kotlin.*
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.context
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith
import org.springframework.context.ApplicationEventPublisher

@RunWith(JUnitPlatform::class)
class StartStageHandlerSpec : Spek({

  val queue: Queue = mock()
  val repository: ExecutionRepository = mock()
  val publisher: ApplicationEventPublisher = mock()
  val clock = fixedClock()

  val handler = StartStageHandler(
    queue,
    repository,
    listOf(
      singleTaskStage,
      multiTaskStage,
      stageWithSyntheticBefore,
      stageWithSyntheticAfter,
      stageWithParallelBranches,
      rollingPushStage
    ),
    publisher,
    clock,
    ContextParameterProcessor()
  )

  fun resetMocks() = reset(queue, repository, publisher)

  describe("starting a stage") {
    context("with a single initial task") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          type = singleTaskStage.type
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("updates the stage status") {
        argumentCaptor<Stage<Pipeline>>().apply {
          verify(repository).storeStage(capture())
          firstValue.status shouldEqual RUNNING
          firstValue.startTime shouldEqual clock.millis()
        }
      }

      it("attaches tasks to the stage") {
        argumentCaptor<Stage<Pipeline>>().apply {
          verify(repository).storeStage(capture())
          firstValue.tasks.size shouldEqual 1
          firstValue.tasks.first().apply {
            id shouldEqual "1"
            name shouldEqual "dummy"
            implementingClass shouldEqual DummyTask::class.java.name
            isStageStart shouldEqual true
            isStageEnd shouldEqual true
          }
        }
      }

      it("starts the first task") {
        verify(queue).push(StartTask(
          message.executionType,
          message.executionId,
          "foo",
          message.stageId,
          "1"
        ))
      }

      it("publishes an event") {
        argumentCaptor<StageStarted>().apply {
          verify(publisher).publishEvent(capture())
          firstValue.apply {
            executionType shouldEqual pipeline.javaClass
            executionId shouldEqual pipeline.id
            stageId shouldEqual message.stageId
          }
        }
      }
    }

    context("with several linear tasks") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          type = multiTaskStage.type
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)
      }

      action("the handler receives a message") {
        handler.handle(message)
      }

      afterGroup(::resetMocks)

      it("attaches tasks to the stage") {
        argumentCaptor<Stage<Pipeline>>().apply {
          verify(repository).storeStage(capture())
          firstValue.apply {
            tasks.size shouldEqual 3
            tasks[0].apply {
              id shouldEqual "1"
              name shouldEqual "dummy1"
              implementingClass shouldEqual DummyTask::class.java.name
              isStageStart shouldEqual true
              isStageEnd shouldEqual false
            }
            tasks[1].apply {
              id shouldEqual "2"
              name shouldEqual "dummy2"
              implementingClass shouldEqual DummyTask::class.java.name
              isStageStart shouldEqual false
              isStageEnd shouldEqual false
            }
            tasks[2].apply {
              id shouldEqual "3"
              name shouldEqual "dummy3"
              implementingClass shouldEqual DummyTask::class.java.name
              isStageStart shouldEqual false
              isStageEnd shouldEqual true
            }
          }
        }
      }

      it("raises an event to indicate the first task is starting") {
        verify(queue).push(StartTask(
          message.executionType,
          message.executionId,
          "foo",
          message.stageId,
          "1"
        ))
      }
    }

    context("with synthetic stages") {
      context("before the main stage") {
        val pipeline = pipeline {
          application = "foo"
          stage {
            type = stageWithSyntheticBefore.type
          }
        }
        val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id)

        beforeGroup {
          whenever(repository.retrievePipeline(message.executionId))
            .thenReturn(pipeline)
        }

        action("the handler receives a message") {
          handler.handle(message)
        }

        afterGroup(::resetMocks)

        it("attaches the synthetic stage to the pipeline") {
          argumentCaptor<Pipeline>().apply {
            verify(repository).store(capture())
            firstValue.stages.size shouldEqual 3
            firstValue.stages.map { it.id } shouldEqual listOf("${message.stageId}-1-pre1", "${message.stageId}-2-pre2", message.stageId)
          }
        }

        it("raises an event to indicate the synthetic stage is starting") {
          verify(queue).push(StartStage(
            message.executionType,
            message.executionId,
            "foo",
            pipeline.stages.first().id
          ))
        }
      }

      context("after the main stage") {
        val pipeline = pipeline {
          application = "foo"
          stage {
            type = stageWithSyntheticAfter.type
          }
        }
        val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id)

        beforeGroup {
          whenever(repository.retrievePipeline(message.executionId))
            .thenReturn(pipeline)
        }

        afterGroup(::resetMocks)

        action("the handler receives a message") {
          handler.handle(message)
        }

        it("attaches the synthetic stage to the pipeline") {
          argumentCaptor<Pipeline>().apply {
            verify(repository).store(capture())
            firstValue.stages.size shouldEqual 3
            firstValue.stages.map { it.id } shouldEqual listOf(message.stageId, "${message.stageId}-1-post1", "${message.stageId}-2-post2")
          }
        }

        it("raises an event to indicate the first task is starting") {
          verify(queue).push(StartTask(
            message.executionType,
            message.executionId,
            "foo",
            message.stageId,
            "1"
          ))
        }
      }
    }

    context("with other upstream stages that are incomplete") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          refId = "1"
          status = SUCCEEDED
          type = singleTaskStage.type
        }
        stage {
          refId = "2"
          status = RUNNING
          type = singleTaskStage.type
        }
        stage {
          refId = "3"
          requisiteStageRefIds = setOf("1", "2")
          type = singleTaskStage.type
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stageByRef("3").id)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("doesn't build its tasks") {
        pipeline.stageByRef("3").tasks shouldMatch isEmpty
      }

      it("waits for the other upstream stage to complete") {
        verify(queue, never()).push(isA<StartTask>())
      }

      it("does not publish an event") {
        verifyZeroInteractions(publisher)
      }
    }

    context("with an execution window") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          type = stageWithSyntheticBefore.type
          context["restrictExecutionDuringTimeWindow"] = true
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("injects a 'wait for execution window' stage before any other synthetic stages") {
        argumentCaptor<Pipeline>().apply {
          verify(repository).store(capture())
          firstValue.stages.size shouldEqual 4
          firstValue.stages.first().apply {
            type shouldEqual RestrictExecutionDuringTimeWindow.TYPE
            parentStageId shouldEqual message.stageId
            syntheticStageOwner shouldEqual STAGE_BEFORE
          }
        }
      }

      it("starts the 'wait for execution window' stage") {
        argumentCaptor<StartStage>().apply {
          verify(queue).push(capture())
          firstValue.stageId shouldEqual pipeline.stages.find { it.type == RestrictExecutionDuringTimeWindow.TYPE }!!.id
        }
      }
    }
  }

  describe("running a branching stage") {
    context("when the stage starts") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          refId = "1"
          type = stageWithParallelBranches.type
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stageByRef("1").id)

      beforeGroup {
        whenever(repository.retrievePipeline(pipeline.id))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("builds tasks for the main branch") {
        val stage = pipeline.stageById(message.stageId)
        stage.tasks.map(Task::getName) shouldEqual listOf("post-branch")
      }

      it("builds synthetic stages for each parallel branch") {
        pipeline.stages.size shouldEqual 4
        assertThat(
          pipeline.stages.map { it.type },
          allElements(equalTo(stageWithParallelBranches.type))
        )
        // TODO: contexts, etc.
      }

      it("runs the parallel stages") {
        argumentCaptor<StartStage>().apply {
          verify(queue, times(3)).push(capture())
          allValues.map { pipeline.stageById(it.stageId).parentStageId } shouldMatch allElements(equalTo(message.stageId))
        }
      }
    }

    context("when one branch starts") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          refId = "1"
          type = stageWithParallelBranches.type
          stageWithParallelBranches.buildSyntheticStages(this)
          stageWithParallelBranches.buildTasks(this)
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages[0].id)

      beforeGroup {
        whenever(repository.retrievePipeline(pipeline.id))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("builds tasks for the branch") {
        val stage = pipeline.stageById(message.stageId)
        assertThat(stage.tasks, !isEmpty)
        stage.tasks.map(Task::getName) shouldEqual listOf("in-branch")
      }

      it("does not build more synthetic stages") {
        val stage = pipeline.stageById(message.stageId)
        pipeline.stages.map(Stage<Pipeline>::getParentStageId) shouldMatch !hasElement(stage.id)
      }
    }
  }

  describe("running a rolling push stage") {
    val pipeline = pipeline {
      application = "foo"
      stage {
        refId = "1"
        type = rollingPushStage.type
      }
    }

    context("when the stage starts") {
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stageByRef("1").id)

      beforeGroup {
        whenever(repository.retrievePipeline(pipeline.id))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("builds tasks for the main branch") {
        pipeline.stageById(message.stageId).let { stage ->
          stage.tasks.size shouldEqual 5
          stage.tasks[0].isLoopStart shouldEqual false
          stage.tasks[1].isLoopStart shouldEqual true
          stage.tasks[2].isLoopStart shouldEqual false
          stage.tasks[3].isLoopStart shouldEqual false
          stage.tasks[4].isLoopStart shouldEqual false
          stage.tasks[0].isLoopEnd shouldEqual false
          stage.tasks[1].isLoopEnd shouldEqual false
          stage.tasks[2].isLoopEnd shouldEqual false
          stage.tasks[3].isLoopEnd shouldEqual true
          stage.tasks[4].isLoopEnd shouldEqual false
        }
      }

      it("runs the parallel stages") {
        argumentCaptor<StartTask>().apply {
          verify(queue).push(capture())
          firstValue.taskId shouldEqual "1"
        }
      }
    }
  }

  describe("running an optional stage") {
    context("if the stage should be run") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          refId = "1"
          type = stageWithSyntheticBefore.type
          context["stageEnabled"] = mapOf(
            "type" to "expression",
            "expression" to "true"
          )
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id)

      beforeGroup {
        whenever(repository.retrievePipeline(pipeline.id))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("proceeds with the first synthetic stage as normal") {
        verify(queue).push(any<StartStage>())
      }
    }

    context("if the stage should be skipped") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          refId = "1"
          type = stageWithSyntheticBefore.type
          context["stageEnabled"] = mapOf(
            "type" to "expression",
            "expression" to "false"
          )
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id)

      beforeGroup {
        whenever(repository.retrievePipeline(pipeline.id))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("skips the stage") {
        argumentCaptor<CompleteStage>().apply {
          verify(queue).push(capture())
          firstValue.status shouldEqual SKIPPED
        }
      }

      it("doesn't build any tasks") {
        pipeline.stageById(message.stageId).tasks shouldMatch isEmpty
      }

      it("doesn't build any synthetic stages") {
        pipeline.stages.filter { it.parentStageId == message.stageId } shouldMatch isEmpty
      }
    }
  }

  describe("invalid commands") {

    val message = StartStage(Pipeline::class.java, "1", "foo", "1")

    describe("no such execution") {
      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId))
          .thenThrow(ExecutionNotFoundException("No Pipeline found for ${message.executionId}"))
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("emits an error event") {
        verify(queue).push(isA<InvalidExecutionId>())
      }
    }

    describe("no such stage") {
      val pipeline = pipeline {
        id = message.executionId
        application = "foo"
      }

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        handler.handle(message)
      }

      it("emits an error event") {
        verify(queue).push(isA<InvalidStageId>())
      }
    }
  }
})