/*
 * Copyright 2016 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.photon.controller.rootscheduler.xenon.task;

import com.vmware.photon.controller.cloudstore.dcp.entity.ImageToImageDatastoreMappingService;
import com.vmware.photon.controller.cloudstore.dcp.entity.ImageToImageDatastoreMappingServiceFactory;
import com.vmware.photon.controller.cloudstore.dcp.helpers.TestEnvironment;
import com.vmware.photon.controller.common.clients.HostClient;
import com.vmware.photon.controller.common.clients.HostClientFactory;
import com.vmware.photon.controller.common.clients.exceptions.SystemErrorException;
import com.vmware.photon.controller.common.xenon.ControlFlags;
import com.vmware.photon.controller.common.xenon.TaskUtils;
import com.vmware.photon.controller.common.xenon.XenonRestClient;
import com.vmware.photon.controller.common.xenon.exceptions.XenonRuntimeException;
import com.vmware.photon.controller.common.zookeeper.gen.ServerAddress;
import com.vmware.photon.controller.host.gen.Host;
import com.vmware.photon.controller.resource.gen.Disk;
import com.vmware.photon.controller.resource.gen.DiskImage;
import com.vmware.photon.controller.resource.gen.Resource;
import com.vmware.photon.controller.resource.gen.ResourceConstraint;
import com.vmware.photon.controller.resource.gen.Vm;
import com.vmware.photon.controller.rootscheduler.Config;
import com.vmware.photon.controller.rootscheduler.SchedulerConfig;
import com.vmware.photon.controller.rootscheduler.exceptions.NoSuchResourceException;
import com.vmware.photon.controller.rootscheduler.helpers.xenon.SchedulerTestEnvironment;
import com.vmware.photon.controller.rootscheduler.helpers.xenon.TestHost;
import com.vmware.photon.controller.rootscheduler.service.ConstraintChecker;
import com.vmware.photon.controller.rootscheduler.xenon.SchedulerXenonHost;
import com.vmware.photon.controller.scheduler.gen.PlaceParams;
import com.vmware.photon.controller.scheduler.gen.PlaceResponse;
import com.vmware.photon.controller.scheduler.gen.PlaceResultCode;
import com.vmware.photon.controller.scheduler.gen.Score;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.common.UriUtils;

import com.google.common.collect.ImmutableMap;
import org.apache.thrift.async.AsyncMethodCallback;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertNotNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * This class implements tests for {@link PlacementTaskService}.
 */
public class PlacementTaskServiceTest {

  private TestHost testHost;
  private PlacementTaskService taskService;

  /**
   * Dummy test case to make IntelliJ recognize this as a test class.
   */
  @Test
  public void dummy() {
  }

  /**
   * Tests the handleStart method.
   */
  public class HandleStartTest {

    @BeforeClass
    public void setUpClass() throws Throwable {
      testHost = TestHost.create();
    }

    @BeforeMethod
    public void setUpTest() {
      taskService = new PlacementTaskService();
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      try {
        testHost.deleteServiceSynchronously();
      } catch (ServiceHost.ServiceNotFoundException e) {
        // Exceptions are expected in the case where a service was not successfully created.
      }
    }

    @AfterClass
    public void tearDownClass() throws Throwable {
      if (testHost != null) {
        TestHost.destroy(testHost);
        testHost = null;
      }
    }

    @Test(dataProvider = "ValidStartStages")
    public void testValidStartStage(TaskState.TaskStage taskStage) throws Throwable {
      PlacementTask startState = buildValidStartState(taskStage);
      Operation startOp = testHost.startServiceSynchronously(taskService, startState);
      assertThat(startOp.getStatusCode(), is(200));

      PlacementTask serviceState = testHost.getServiceState(PlacementTask.class);
      assertThat(serviceState.controlFlags, is(ControlFlags.CONTROL_FLAG_OPERATION_PROCESSING_DISABLED));
    }

    @DataProvider(name = "ValidStartStages")
    public Object[][] getValidStartStages() {
      return new Object[][]{
          {TaskState.TaskStage.CREATED},
          {TaskState.TaskStage.STARTED},
          {TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.CANCELLED},
      };
    }

    @Test(dataProvider = "TerminalStartStages")
    public void testTerminalStartStage(TaskState.TaskStage taskStage) throws Throwable {
      PlacementTask startState = buildValidStartState(taskStage);
      Operation op = testHost.startServiceSynchronously(taskService, startState);
      assertThat(op.getStatusCode(), is(200));

      PlacementTask serviceState = testHost.getServiceState(PlacementTask.class);
      assertThat(serviceState.taskState.stage, is(taskStage));
    }

    @DataProvider(name = "TerminalStartStages")
    public Object[][] getTerminalStartStages() {
      return new Object[][]{
          {TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.CANCELLED},
      };
    }
  }

  /**
   * Tests for the handlePatch method.
   */
  public class HandlePatchTest {

    @BeforeClass
    public void setUpClass() throws Throwable {
      testHost = TestHost.create();
    }

    @BeforeMethod
    public void setUpTest() {
      taskService = new PlacementTaskService();
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      try {
        testHost.deleteServiceSynchronously();
      } catch (ServiceHost.ServiceNotFoundException e) {
        // Exceptions are expected in the case where a taskService instance was not successfully created.
      }
    }

    @AfterClass
    public void tearDownClass() throws Throwable {
      if (testHost != null) {
        TestHost.destroy(testHost);
        testHost = null;
      }
    }

    @Test(dataProvider = "ValidStageTransitions")
    public void testValidStageTransition(TaskState.TaskStage startStage, TaskState.TaskStage patchStage)
        throws Throwable {
      PlacementTask startState = buildValidStartState(startStage);
      Operation op = testHost.startServiceSynchronously(taskService, startState);
      assertThat(op.getStatusCode(), is(200));

      Operation patchOperation = Operation
          .createPatch(UriUtils.buildUri(testHost, TestHost.SERVICE_URI))
          .setBody(taskService.buildPatch(patchStage, false, null));

      op = testHost.sendRequestAndWait(patchOperation);
      assertThat(op.getStatusCode(), is(200));

      PlacementTask serviceState = testHost.getServiceState(PlacementTask.class);
      assertThat(serviceState.taskState.stage, is(patchStage));
      assertThat(serviceState.controlFlags, is(ControlFlags.CONTROL_FLAG_OPERATION_PROCESSING_DISABLED));
    }

    @DataProvider(name = "ValidStageTransitions")
    public Object[][] getValidStageTransitions() {
      return new Object[][]{
          {TaskState.TaskStage.CREATED, TaskState.TaskStage.STARTED},
          {TaskState.TaskStage.CREATED, TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.CREATED, TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.CREATED, TaskState.TaskStage.CANCELLED},

          {TaskState.TaskStage.STARTED, TaskState.TaskStage.STARTED},
          {TaskState.TaskStage.STARTED, TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.STARTED, TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.STARTED, TaskState.TaskStage.CANCELLED},
      };
    }

    @Test(dataProvider = "InvalidStageTransitions", expectedExceptions = XenonRuntimeException.class)
    public void testInvalidStageTransition(TaskState.TaskStage startStage, TaskState.TaskStage patchStage)
        throws Throwable {
      PlacementTask startState = buildValidStartState(startStage);
      Operation op = testHost.startServiceSynchronously(taskService, startState);
      assertThat(op.getStatusCode(), is(200));

      Operation patchOperation = Operation
          .createPatch(UriUtils.buildUri(testHost, TestHost.SERVICE_URI))
          .setBody(taskService.buildPatch(patchStage, false, null));

      testHost.sendRequestAndWait(patchOperation);
    }

    @DataProvider(name = "InvalidStageTransitions")
    public Object[][] getInvalidStageTransitions() {
      return new Object[][]{
          {TaskState.TaskStage.CREATED, TaskState.TaskStage.CREATED},

          {TaskState.TaskStage.STARTED, TaskState.TaskStage.CREATED},

          {TaskState.TaskStage.FINISHED, TaskState.TaskStage.CREATED},
          {TaskState.TaskStage.FINISHED, TaskState.TaskStage.STARTED},
          {TaskState.TaskStage.FINISHED, TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.FINISHED, TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.FINISHED, TaskState.TaskStage.CANCELLED},

          {TaskState.TaskStage.FAILED, TaskState.TaskStage.CREATED},
          {TaskState.TaskStage.FAILED, TaskState.TaskStage.STARTED},
          {TaskState.TaskStage.FAILED, TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.FAILED, TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.FAILED, TaskState.TaskStage.CANCELLED},

          {TaskState.TaskStage.CANCELLED, TaskState.TaskStage.CREATED},
          {TaskState.TaskStage.CANCELLED, TaskState.TaskStage.STARTED},
          {TaskState.TaskStage.CANCELLED, TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.CANCELLED, TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.CANCELLED, TaskState.TaskStage.CANCELLED},
      };
    }
  }

  /**
   * End-to-end tests for {@link PlacementTaskService} task.
   */
  public class EndToEndTest {
    Random random = new Random();
    @Mock
    private Config config;

    @Mock
    private HostClient client;

    @Mock
    private ConstraintChecker checker;

    @Mock
    private XenonRestClient xenonRestClient;

    @Mock
    private HostClientFactory hostClientFactory;

    private SchedulerTestEnvironment schedulerTestEnvironment;

    @BeforeMethod
    public void setUpTest() throws Throwable {
      MockitoAnnotations.initMocks(this);
      PlaceParams rootPlaceParams = new PlaceParams();
      rootPlaceParams.setMaxFanoutCount(4);
      rootPlaceParams.setTimeout(20000);
      SchedulerConfig schedulerConfig = new SchedulerConfig();
      schedulerConfig.setUtilizationTransferRatio(0.5);
      config.initRootPlaceParams();
      doReturn(schedulerConfig).when(config).getRoot();
      doReturn(rootPlaceParams).when(config).getRootPlaceParams();
      when(hostClientFactory.create()).thenReturn(client);
      schedulerTestEnvironment = SchedulerTestEnvironment.create(
          hostClientFactory, config, checker, xenonRestClient, 1);
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      if (schedulerTestEnvironment != null) {
        schedulerTestEnvironment.stop();
        schedulerTestEnvironment = null;
      }
      reset(client);
    }

    /**
     * Test no candidates to select.
     */
    @Test
    public void testEndToEndNoCandidates() throws Throwable {
      Resource resource = new Resource();

      doReturn(ImmutableMap.of()).when(checker)
          .getCandidates(anyListOf(ResourceConstraint.class), anyInt());

      PlacementTask placementTask = new PlacementTask();
      placementTask.resource = resource;
      placementTask.sampleHostCount = config.getRootPlaceParams().getMaxFanoutCount();
      placementTask.timeoutMs = config.getRootPlaceParams().getTimeout();
      placementTask.taskState = new TaskState();
      placementTask.taskState.stage = TaskState.TaskStage.CREATED;
      placementTask.taskState.isDirect = true;

      PlacementTask finalState = schedulerTestEnvironment.callServiceAndWaitForState(
          PlacementTaskService.FACTORY_LINK,
          placementTask,
          PlacementTask.class,
          (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FAILED));
      assertThat(finalState.resultCode, is(PlaceResultCode.NO_SUCH_RESOURCE));
      assertThat(finalState.error, containsString("Place failure, constraints cannot be satisfied for request"));
      verifyNoMoreInteractions(client);
    }

    /**
     * Test no hosts return placements.
     */
    @Test
    public void testPlaceNoResponses() throws Throwable {
      Resource resource = new Resource();

      ImmutableMap<String, ServerAddress> matches = ImmutableMap.of(
          "h1", new ServerAddress("h1", 1234),
          "h2", new ServerAddress("h2", 1234),
          "h3", new ServerAddress("h3", 1234),
          "h4", new ServerAddress("h4", 1234));

      doReturn(matches).when(checker)
          .getCandidates(anyListOf(ResourceConstraint.class), anyInt());

      PlacementTask placementTask = new PlacementTask();
      placementTask.resource = resource;
      placementTask.sampleHostCount = config.getRootPlaceParams().getMaxFanoutCount();
      placementTask.timeoutMs = config.getRootPlaceParams().getTimeout();
      placementTask.taskState = new TaskState();
      placementTask.taskState.stage = TaskState.TaskStage.CREATED;
      placementTask.taskState.isDirect = true;

      doAnswer((InvocationOnMock invocation) -> {
        Object[] arguments = invocation.getArguments();
        AsyncMethodCallback<Host.AsyncClient.place_call> call =
            (AsyncMethodCallback<Host.AsyncClient.place_call>) arguments[1];
        call.onError(new Exception());
        return null;
      }).when(client).place(any(), any());

      PlacementTask finalState = schedulerTestEnvironment.callServiceAndWaitForState(
          PlacementTaskService.FACTORY_LINK,
          placementTask,
          PlacementTask.class,
          (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FAILED));
      assertThat(finalState.resultCode, is(PlaceResultCode.SYSTEM_ERROR));
      assertThat(finalState.error, containsString("Received no response in"));
      verify(client, times(4)).place(any(), any());
    }

    /**
     * Test success when two hosts responds successfully and two fail with an error.
     */
    @Test
    public void testPlacePartialSuccess() throws Throwable {
      Resource resource = new Resource();

      ImmutableMap<String, ServerAddress> matches = ImmutableMap.of(
          "h1", new ServerAddress("h1", 1234),
          "h2", new ServerAddress("h2", 1234),
          "h3", new ServerAddress("h3", 1234),
          "h4", new ServerAddress("h4", 1234));

      doReturn(matches).when(checker)
          .getCandidates(anyListOf(ResourceConstraint.class), anyInt());

      PlacementTask placementTask = new PlacementTask();
      placementTask.resource = resource;
      placementTask.sampleHostCount = config.getRootPlaceParams().getMaxFanoutCount();
      placementTask.timeoutMs = config.getRootPlaceParams().getTimeout();
      placementTask.taskState = new TaskState();
      placementTask.taskState.stage = TaskState.TaskStage.CREATED;
      placementTask.taskState.isDirect = false;

      int numResponses = 2;
      Set<PlaceResponse> responses = new HashSet<>();
      doAnswer((InvocationOnMock invocation) -> {
        Object[] arguments = invocation.getArguments();
        AsyncMethodCallback<Host.AsyncClient.place_call> call =
            (AsyncMethodCallback<Host.AsyncClient.place_call>) arguments[1];
        if (responses.size() < numResponses) {
          PlaceResponse response = new PlaceResponse(PlaceResultCode.OK);
          response.setScore(new Score(random.nextInt(), random.nextInt()));
          responses.add(response);
          Host.AsyncClient.place_call placeResponse = mock(Host.AsyncClient.place_call.class);
          doReturn(response).when(placeResponse).getResult();
          call.onComplete(placeResponse);
        } else {
          call.onError(new Exception());
        }
        return null;
      }).when(client).place(any(), any());

      PlacementTask finalState = schedulerTestEnvironment.callServiceAndWaitForState(
          PlacementTaskService.FACTORY_LINK,
          placementTask,
          PlacementTask.class,
          (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FINISHED));
      assertThat(finalState.resultCode, is(PlaceResultCode.OK));
      assertThat(finalState.error, isEmptyOrNullString());
      verify(client, times(4)).place(any(), any());
    }

    /**
     * Test success all hosts return placements where the task will complete before returning.
     */
    @Test
    public void testEndToEndSuccessDirect() throws Throwable {
      Resource resource = new Resource();

      ImmutableMap<String, ServerAddress> matches = ImmutableMap.of(
          "h1", new ServerAddress("h1", 1234),
          "h2", new ServerAddress("h2", 1234),
          "h3", new ServerAddress("h3", 1234),
          "h4", new ServerAddress("h4", 1234));

      doReturn(matches).when(checker)
          .getCandidates(anyListOf(ResourceConstraint.class), anyInt());

      PlacementTask placementTask = new PlacementTask();
      placementTask.resource = resource;
      placementTask.sampleHostCount = config.getRootPlaceParams().getMaxFanoutCount();
      placementTask.timeoutMs = config.getRootPlaceParams().getTimeout();
      placementTask.taskState = new TaskState();
      placementTask.taskState.stage = TaskState.TaskStage.CREATED;
      placementTask.taskState.isDirect = true;

      Set<PlaceResponse> responses = new HashSet<>();
      doAnswer((InvocationOnMock invocation) -> {
        Object[] arguments = invocation.getArguments();
        AsyncMethodCallback<Host.AsyncClient.place_call> call =
            (AsyncMethodCallback<Host.AsyncClient.place_call>) arguments[1];
        PlaceResponse response = new PlaceResponse(PlaceResultCode.OK);
        response.setScore(new Score(random.nextInt(), random.nextInt()));
        responses.add(response);
        Host.AsyncClient.place_call placeResponse = mock(Host.AsyncClient.place_call.class);
        doReturn(response).when(placeResponse).getResult();
        call.onComplete(placeResponse);
        return null;
      }).when(client).place(any(), any());

      Operation operation = schedulerTestEnvironment.sendPostAndWait(
          PlacementTaskService.FACTORY_LINK,
          placementTask);

      PlacementTask finalState = operation.getBody(PlacementTask.class);

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FINISHED));
      assertThat(finalState.resultCode, is(PlaceResultCode.OK));
      assertThat(finalState.error, isEmptyOrNullString());
      verify(client, times(4)).place(any(), any());
    }

    /**
     * Test success all hosts return placement that will poll the task until it is completed.
     */
    @Test
    public void testEndToEndSuccessNotDirect() throws Throwable {
      Resource resource = new Resource();

      ImmutableMap<String, ServerAddress> matches = ImmutableMap.of(
          "h1", new ServerAddress("h1", 1234),
          "h2", new ServerAddress("h2", 1234),
          "h3", new ServerAddress("h3", 1234),
          "h4", new ServerAddress("h4", 1234));

      doReturn(matches).when(checker)
          .getCandidates(anyListOf(ResourceConstraint.class), anyInt());

      PlacementTask placementTask = new PlacementTask();
      placementTask.resource = resource;
      placementTask.sampleHostCount = config.getRootPlaceParams().getMaxFanoutCount();
      placementTask.timeoutMs = config.getRootPlaceParams().getTimeout();
      placementTask.taskState = new TaskState();
      placementTask.taskState.stage = TaskState.TaskStage.CREATED;
      placementTask.taskState.isDirect = false;

      Set<PlaceResponse> responses = new HashSet<>();
      doAnswer((InvocationOnMock invocation) -> {
        Object[] arguments = invocation.getArguments();
        AsyncMethodCallback<Host.AsyncClient.place_call> call =
            (AsyncMethodCallback<Host.AsyncClient.place_call>) arguments[1];
        PlaceResponse response = new PlaceResponse(PlaceResultCode.OK);
        response.setScore(new Score(random.nextInt(), random.nextInt()));
        responses.add(response);
        Host.AsyncClient.place_call placeResponse = mock(Host.AsyncClient.place_call.class);
        doReturn(response).when(placeResponse).getResult();
        call.onComplete(placeResponse);
        return null;
      }).when(client).place(any(), any());

      PlacementTask finalState = schedulerTestEnvironment.callServiceAndWaitForState(
          PlacementTaskService.FACTORY_LINK,
          placementTask,
          PlacementTask.class,
          (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FINISHED));
      assertThat(finalState.resultCode, is(PlaceResultCode.OK));
      assertThat(finalState.error, isEmptyOrNullString());
      verify(client, times(4)).place(any(), any());
    }
  }

  /**
   * Image seeding tests.
   */
  public class ImageSeedingTest {
    final String imageId = "test-image-id";
    final String imageDatastoreId = "test-image-datastoreId";

    @Mock
    private SchedulerXenonHost schedulerXenonHost;

    TestEnvironment cloudStoreMachine;

    private PlacementTaskService taskService;

    @BeforeClass
    public void testSetup() throws Throwable {
      MockitoAnnotations.initMocks(this);

      cloudStoreMachine = TestEnvironment.create(1);
      XenonRestClient cloudStoreClient = new XenonRestClient(
          cloudStoreMachine.getServerSet(), Executors.newFixedThreadPool(1));
      cloudStoreClient.start();

      ImageToImageDatastoreMappingService.State state = new ImageToImageDatastoreMappingService.State();
      state.imageId = imageId;
      state.imageDatastoreId = imageDatastoreId;

      cloudStoreMachine.sendPostAndWait(ImageToImageDatastoreMappingServiceFactory.SELF_LINK, state);

      taskService = new PlacementTaskService();
      taskService.setHost(schedulerXenonHost);
      doReturn(cloudStoreClient).when(schedulerXenonHost)
          .getCloudStoreClient();
    }

    @AfterClass
    public void testCleanup() throws Throwable {
      cloudStoreMachine.stop();
    }

    @Test
    public void testSuccess() throws Throwable {
      ResourceConstraint constraint = taskService.createImageSeedingConstraint(createVmResource(imageId));
      assertNotNull(constraint);
      assertThat(constraint.getValues().contains(imageDatastoreId), is(true));
    }

    @Test(expectedExceptions = NoSuchResourceException.class)
    public void testWithZeroDatastores() throws Throwable {
      taskService.createImageSeedingConstraint(createVmResource("new-test-image-id"));
    }

    @Test(expectedExceptions = SystemErrorException.class)
    public void testWithNoDiskImages() throws Throwable {
      taskService.createImageSeedingConstraint(createVmResource(null));
    }

    private Vm createVmResource(String imageId) {
      Disk disk = new Disk();

      if (imageId != null) {
        DiskImage image = new DiskImage();
        image.setId(imageId);
        disk.setImage(image);
      }

      Vm vm = new Vm();
      vm.setDisks(Arrays.asList(disk));
      return vm;
    }
  }

  private PlacementTask buildValidStartState(TaskState.TaskStage stage) {
    PlacementTask startState = new PlacementTask();
    startState.controlFlags = ControlFlags.CONTROL_FLAG_OPERATION_PROCESSING_DISABLED;
    if (stage != null) {
      startState.taskState = new TaskState();
      startState.taskState.stage = stage;
      startState.taskState.isDirect = true;
    }

    return startState;
  }
}
