/*
* Copyright 2022 YugaByte, Inc. and Contributors
*
* Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
* may not use this file except in compliance with the License. You
* may obtain a copy of the License at
*
https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
*/
package com.yugabyte.yw.commissioner.tasks;

import java.util.UUID;

import javax.inject.Inject;

import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.forms.ResizeNodeParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Universe;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UpdateKubernetesDiskSize extends EditKubernetesUniverse {

  @Inject
  protected UpdateKubernetesDiskSize(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  @Override
  protected ResizeNodeParams taskParams() {
    return (ResizeNodeParams) taskParams;
  }

  @Override
  public void run() {
    try {
      checkUniverseVersion();
      verifyParams(UniverseOpType.EDIT);
      // additional verification about disk size increase is needed here

      Universe universe = lockUniverseForUpdate(taskParams().expectedUniverseVersion);
      taskParams().useNewHelmNamingStyle = universe.getUniverseDetails().useNewHelmNamingStyle;
      preTaskActions();

      UserIntent userIntent = universe.getUniverseDetails().getPrimaryCluster().userIntent;
      Integer newDiskSize = taskParams().getPrimaryCluster().userIntent.deviceInfo.volumeSize;
      // String newDiskSizeGi = String.format("%dGi", newDiskSize);
      userIntent.deviceInfo.volumeSize = newDiskSize;
      // String softwareVersion = userIntent.ybSoftwareVersion;
      // primary and readonly clusters disk resize
      for (UniverseDefinitionTaskParams.Cluster cluster : taskParams().clusters) {
        Provider provider = Provider.getOrBadRequest(UUID.fromString(cluster.userIntent.provider));
        boolean isReadOnlyCluster =
            cluster.clusterType == UniverseDefinitionTaskParams.ClusterType.ASYNC;
        KubernetesPlacement placement =
            new KubernetesPlacement(cluster.placementInfo, isReadOnlyCluster);
        String masterAddresses =
            PlacementInfoUtil.computeMasterAddresses(
                cluster.placementInfo,
                placement.masters,
                taskParams().nodePrefix,
                provider,
                universe.getUniverseDetails().communicationPorts.masterRpcPort,
                taskParams().useNewHelmNamingStyle,
                provider.getK8sPodAddrTemplate());
        UserIntent newIntent = taskParams().getPrimaryCluster().userIntent;
        // run the disk resize tasks for each AZ in the Cluster
        createResizeDiskTask(placement, masterAddresses, newIntent, isReadOnlyCluster);
      }

      // persist the changes to the universe
      createPersistResizeNodeTask(
          userIntent.instanceType,
          taskParams().getPrimaryCluster().userIntent.deviceInfo.volumeSize);

      // Marks update of this universe as a success only if all the tasks before it
      // succeeded.
      createMarkUniverseUpdateSuccessTasks()
          .setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);
      // Run all the tasks.
      getRunnableTask().runSubTasks();
    } finally {
      unlockUniverseForUpdate();
    }
    log.info("Finished {} task.", getName());
  }
}
