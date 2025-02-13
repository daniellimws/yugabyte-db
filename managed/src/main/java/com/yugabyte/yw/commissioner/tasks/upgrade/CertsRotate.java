// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks.upgrade;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;

import org.apache.commons.lang3.tuple.Pair;

import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.TaskExecutor.SubTaskGroup;
import com.yugabyte.yw.commissioner.UpgradeTaskBase;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.commissioner.tasks.subtasks.AnsibleConfigureServers;
import com.yugabyte.yw.commissioner.tasks.subtasks.CertReloadTaskCreator;
import com.yugabyte.yw.commissioner.tasks.subtasks.UniverseSetTlsParams;
import com.yugabyte.yw.commissioner.tasks.subtasks.UniverseUpdateRootCert;
import com.yugabyte.yw.commissioner.tasks.subtasks.UniverseUpdateRootCert.UpdateRootCertAction;
import com.yugabyte.yw.common.NodeManager;
import com.yugabyte.yw.common.NodeManager.CertRotateAction;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.common.utils.Version;
import com.yugabyte.yw.forms.CertsRotateParams;
import com.yugabyte.yw.forms.CertsRotateParams.CertRotationType;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import com.yugabyte.yw.forms.UpgradeTaskParams.UpgradeOption;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.NodeDetails.NodeState;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CertsRotate extends UpgradeTaskBase {

  private static final String KEY_CERT_ROTATE_SUPPORTED_VERSIONS =
      "yb.features.cert_rotate.supportedVersions";

  @Inject private RuntimeConfigFactory runtimeConfigFactory;

  @Inject
  protected CertsRotate(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  @Override
  protected CertsRotateParams taskParams() {
    return (CertsRotateParams) taskParams;
  }

  @Override
  public SubTaskGroupType getTaskSubGroupType() {
    return SubTaskGroupType.RotatingCert;
  }

  @Override
  public NodeState getNodeState() {
    return NodeState.UpdateCert;
  }

  @Override
  public void run() {
    runUpgrade(
        () -> {
          Pair<List<NodeDetails>, List<NodeDetails>> nodes = fetchNodes(taskParams().upgradeOption);
          Set<NodeDetails> allNodes = toOrderedSet(nodes);
          // Verify the request params and fail if invalid
          taskParams().verifyParams(getUniverse());
          // For rootCA root certificate rotation, we would need to do it in three rounds
          // so that node to node communications are not disturbed during the upgrade
          // For other cases we can do it in one round by updating respective certs
          if (taskParams().upgradeOption == UpgradeOption.ROLLING_UPGRADE
              && taskParams().rootCARotationType == CertRotationType.RootCert) {
            // Update the rootCA in platform to have both old cert and new cert
            createUniverseUpdateRootCertTask(UpdateRootCertAction.MultiCert);
            // Append new root cert to the existing ca.crt
            createCertUpdateTasks(allNodes, CertRotateAction.APPEND_NEW_ROOT_CERT);

            // Add task to use the updated certs
            createActivateCertsTask(getUniverse(), nodes, UpgradeOption.ROLLING_UPGRADE);

            // Copy new server certs to all nodes
            createCertUpdateTasks(allNodes, CertRotateAction.ROTATE_CERTS);

            // Add task to use the updated certs
            createActivateCertsTask(getUniverse(), nodes, UpgradeOption.ROLLING_UPGRADE);

            // Remove old root cert from the ca.crt
            createCertUpdateTasks(allNodes, CertRotateAction.REMOVE_OLD_ROOT_CERT);
            // Update gflags of cert directories
            createUpdateCertDirsTask(nodes.getLeft(), ServerType.MASTER);
            createUpdateCertDirsTask(nodes.getRight(), ServerType.TSERVER);

            // Reset the old rootCA content in platform
            createUniverseUpdateRootCertTask(UpdateRootCertAction.Reset);
            // Update universe details with new cert values
            createUniverseSetTlsParamsTask(getTaskSubGroupType());

            // Add task to use the updated certs
            createActivateCertsTask(getUniverse(), nodes, UpgradeOption.ROLLING_UPGRADE);

          } else {
            // Update the rootCA in platform to have both old cert and new cert
            if (taskParams().rootCARotationType == CertRotationType.RootCert) {
              createUniverseUpdateRootCertTask(UpdateRootCertAction.MultiCert);
            }
            createCertUpdateTasks(
                nodes.getLeft(),
                nodes.getRight(),
                getTaskSubGroupType(),
                taskParams().rootCARotationType,
                taskParams().clientRootCARotationType);

            // Add task to use the updated certs
            createActivateCertsTask(getUniverse(), nodes, taskParams().upgradeOption);

            // Reset the old rootCA content in platform
            if (taskParams().rootCARotationType == CertRotationType.RootCert) {
              createUniverseUpdateRootCertTask(UpdateRootCertAction.Reset);
            }
            // Update universe details with new cert values
            createUniverseSetTlsParamsTask(getTaskSubGroupType());
          }
        });
  }

  private void createCertUpdateTasks(
      Collection<NodeDetails> nodes, CertRotateAction certRotateAction) {
    createCertUpdateTasks(
        nodes,
        certRotateAction,
        getTaskSubGroupType(),
        taskParams().rootCARotationType,
        taskParams().clientRootCARotationType);
  }

  private void createUniverseUpdateRootCertTask(UpdateRootCertAction updateAction) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("UniverseUpdateRootCert", executor);
    UniverseUpdateRootCert.Params params = new UniverseUpdateRootCert.Params();
    params.universeUUID = taskParams().universeUUID;
    params.rootCA = taskParams().rootCA;
    params.action = updateAction;
    UniverseUpdateRootCert task = createTask(UniverseUpdateRootCert.class);
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    subTaskGroup.setSubTaskGroupType(getTaskSubGroupType());
    getRunnableTask().addSubTaskGroup(subTaskGroup);
  }

  private void createUpdateCertDirsTask(Collection<NodeDetails> nodes, ServerType serverType) {
    createUpdateCertDirsTask(nodes, serverType, getTaskSubGroupType());
  }

  // TODO: sort out the mess with rootAndClientRootCASame silently shadowing its namesake
  // in UniverseDefinitionTaskParams
  // (referencing them through taskParams() may cause subtle bugs)
  @Override
  protected UniverseSetTlsParams.Params createSetTlsParams(SubTaskGroupType subTaskGroupType) {
    UniverseSetTlsParams.Params params = super.createSetTlsParams(subTaskGroupType);
    params.rootAndClientRootCASame = taskParams().rootAndClientRootCASame;
    return params;
  }

  @Override
  protected AnsibleConfigureServers.Params createCertUpdateParams(
      UserIntent userIntent,
      NodeDetails node,
      NodeManager.CertRotateAction certRotateAction,
      CertsRotateParams.CertRotationType rootCARotationType,
      CertsRotateParams.CertRotationType clientRootCARotationType) {
    AnsibleConfigureServers.Params params =
        super.createCertUpdateParams(
            userIntent, node, certRotateAction, rootCARotationType, clientRootCARotationType);
    params.rootAndClientRootCASame = taskParams().rootAndClientRootCASame;
    return params;
  }

  @Override
  protected AnsibleConfigureServers.Params createUpdateCertDirParams(
      UserIntent userIntent, NodeDetails node, ServerType serverType) {
    AnsibleConfigureServers.Params params =
        super.createUpdateCertDirParams(userIntent, node, serverType);
    params.rootAndClientRootCASame = taskParams().rootAndClientRootCASame;
    return params;
  }

  /**
   * compare the universe version against the versions where cert rotate is supported and
   * appropriately call 'cert rotate' for newer universes or 'restart nodes' for older universes
   *
   * @param universe
   * @param nodes nodes which are to be activated with new certs
   * @param upgradeOption
   */
  private void createActivateCertsTask(
      Universe universe,
      Pair<List<NodeDetails>, List<NodeDetails>> nodes,
      UpgradeOption upgradeOption) {

    if (isCertReloadable(universe)) {
      // cert rotate can be performed
      log.info("adding cert rotate via reload task ...");
      createCertReloadTask(nodes, universe.universeUUID, userTaskUUID);

    } else {
      // Do a rolling restart
      log.info("adding a cert rotate via restart task ...");
      createRestartTasks(nodes, upgradeOption, false);
    }
  }

  private boolean isCertReloadable(Universe universe) {
    List<?> supportedVersions =
        this.runtimeConfigFactory
            .staticApplicationConf()
            .getAnyRefList(KEY_CERT_ROTATE_SUPPORTED_VERSIONS);
    Version ybSoftwareVersion =
        new Version(universe.getUniverseDetails().getPrimaryCluster().userIntent.ybSoftwareVersion);
    Optional<?> matchingSupportedVersion =
        supportedVersions
            .stream()
            .filter(
                v -> {
                  log.debug(
                      "comparing universe version {} with supported version {}",
                      ybSoftwareVersion,
                      v);
                  Version supportedVersion = new Version(v.toString());
                  int comparisonValue = supportedVersion.compareTo(ybSoftwareVersion);
                  log.debug("comparison result = {}", comparisonValue);
                  return comparisonValue == 0;
                })
            .findFirst();
    return matchingSupportedVersion.isPresent();
  }

  protected void createCertReloadTask(
      Pair<List<NodeDetails>, List<NodeDetails>> nodesPair, UUID universeUuid, UUID userTaskUuid) {

    if (nodesPair == null) {
      return; // nothing to do if node details are missing
    }
    log.debug("creating certReloadTaskCreator ...");

    CertReloadTaskCreator taskCreator =
        new CertReloadTaskCreator(
            universeUuid, userTaskUuid, getRunnableTask(), getTaskExecutor(), nodesPair.getKey());

    createNonRestartUpgradeTaskFlow(
        taskCreator, nodesPair, DEFAULT_CONTEXT, taskParams().ybcInstalled);
  }
}
