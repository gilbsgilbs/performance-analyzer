/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

/*
 * Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.opensearch.performanceanalyzer.config.setting;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.ActionListener;
import org.opensearch.action.admin.cluster.settings.ClusterUpdateSettingsRequest;
import org.opensearch.action.admin.cluster.state.ClusterStateRequest;
import org.opensearch.action.admin.cluster.state.ClusterStateResponse;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateListener;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.performanceanalyzer.OpenSearchResources;
import org.opensearch.performanceanalyzer.PerformanceAnalyzerApp;
import org.opensearch.performanceanalyzer.rca.framework.metrics.WriterMetrics;

/**
 * Class that handles updating cluster settings, and notifying the listeners when cluster settings
 * change.
 */
public class ClusterSettingsManager implements ClusterStateListener {
    private static final Logger LOG = LogManager.getLogger(ClusterSettingsManager.class);
    private final Map<Setting<Integer>, List<ClusterSettingListener<Integer>>>
            intSettingListenerMap = new HashMap<>();
    private final Map<Setting<String>, List<ClusterSettingListener<String>>>
            stringSettingListenerMap = new HashMap<>();
    private final List<Setting<Integer>> managedIntSettings = new ArrayList<>();
    private final List<Setting<String>> managedStringSettings = new ArrayList<>();
    private final ClusterSettingsResponseHandler clusterSettingsResponseHandler;

    private boolean initialized = false;

    public ClusterSettingsManager(
            List<Setting<Integer>> intSettings, List<Setting<String>> stringSettings) {
        managedIntSettings.addAll(intSettings);
        managedStringSettings.addAll(stringSettings);
        this.clusterSettingsResponseHandler = new ClusterSettingsResponseHandler();
    }

    /**
     * Adds a listener that will be called when the requested setting's value changes.
     *
     * @param setting The setting that needs to be listened to.
     * @param listener The listener object that will be called when the setting changes.
     */
    public void addSubscriberForIntSetting(
            Setting<Integer> setting, ClusterSettingListener<Integer> listener) {
        if (intSettingListenerMap.containsKey(setting)) {
            final List<ClusterSettingListener<Integer>> currentListeners =
                    intSettingListenerMap.get(setting);
            if (!currentListeners.contains(listener)) {
                currentListeners.add(listener);
                intSettingListenerMap.put(setting, currentListeners);
            }
        } else {
            intSettingListenerMap.put(setting, Collections.singletonList(listener));
        }
    }

    /**
     * Adds a listener that will be called when the requested setting's value changes.
     *
     * @param setting The setting that needs to be listened to.
     * @param listener The listener object that will be called when the setting changes.
     */
    public void addSubscriberForStringSetting(
            Setting<String> setting, ClusterSettingListener<String> listener) {
        if (stringSettingListenerMap.containsKey(setting)) {
            final List<ClusterSettingListener<String>> currentListeners =
                    stringSettingListenerMap.get(setting);
            if (!currentListeners.contains(listener)) {
                currentListeners.add(listener);
                stringSettingListenerMap.put(setting, currentListeners);
            }
        } else {
            stringSettingListenerMap.put(setting, Collections.singletonList(listener));
        }
    }
    /** Bootstraps the listeners and tries to read initial values for cluster settings. */
    public void initialize() {
        if (!initialized) {
            // When an OpenSearch node is just started, the plugin initialization happens
            // before there is any cluster state set. So, check if there is a cluster
            // state first, if there is no cluster state, register a ClusterStateListener
            // before trying to read the initial cluster setting values.
            if (clusterStatePresent()) {
                registerSettingUpdateListener();
                readAllManagedClusterSettings();
            } else {
                registerClusterStateListener(this);
            }

            initialized = true;
        }
    }

    /**
     * Updates the requested setting with the requested value across the cluster.
     *
     * @param setting The setting that needs to be updated.
     * @param newValue The new value for the setting.
     */
    public void updateSetting(final Setting<Integer> setting, final Integer newValue) {
        final ClusterUpdateSettingsRequest request = new ClusterUpdateSettingsRequest();
        request.persistentSettings(Settings.builder().put(setting.getKey(), newValue).build());
        OpenSearchResources.INSTANCE.getClient().admin().cluster().updateSettings(request);
    }

    /**
     * Updates the requested setting with the requested value across the cluster.
     *
     * @param setting The setting that needs to be updated.
     * @param newValue The new value for the setting.
     */
    public void updateSetting(final Setting<String> setting, final String newValue) {
        final ClusterUpdateSettingsRequest request = new ClusterUpdateSettingsRequest();
        request.persistentSettings(Settings.builder().put(setting.getKey(), newValue).build());
        OpenSearchResources.INSTANCE.getClient().admin().cluster().updateSettings(request);
    }

    /** Registers a setting update listener for all the settings managed by this instance. */
    private void registerSettingUpdateListener() {
        for (Setting<Integer> setting : managedIntSettings) {
            OpenSearchResources.INSTANCE
                    .getClusterService()
                    .getClusterSettings()
                    .addSettingsUpdateConsumer(
                            setting, updatedVal -> callIntSettingListeners(setting, updatedVal));
        }

        for (Setting<String> setting : managedStringSettings) {
            OpenSearchResources.INSTANCE
                    .getClusterService()
                    .getClusterSettings()
                    .addSettingsUpdateConsumer(
                            setting, updatedVal -> callStringSettingListeners(setting, updatedVal));
        }
    }

    /**
     * Checks if there is a cluster state set.
     *
     * @return true if a cluster state is set, false otherwise.
     */
    private boolean clusterStatePresent() {
        try {
            final ClusterState clusterState =
                    OpenSearchResources.INSTANCE.getClusterService().state();
            return clusterState != null;
        } catch (Exception | AssertionError t) {
            LOG.error("Unable to retrieve cluster state: Exception: {}", t.getMessage());
            LOG.error(t);
        }

        return false;
    }

    /** Reads all the cluster settings managed by this instance. */
    private void readAllManagedClusterSettings() {
        LOG.debug("Trying to read initial cluster settings");
        final ClusterStateRequest clusterStateRequest = new ClusterStateRequest();
        clusterStateRequest.routingTable(false).nodes(false);
        OpenSearchResources.INSTANCE
                .getClient()
                .admin()
                .cluster()
                .state(clusterStateRequest, clusterSettingsResponseHandler);
    }

    /**
     * Registers a ClusterStateListener with the cluster service. The listener is called when the
     * cluster state changes.
     *
     * @param clusterStateListener The listener to be registered.
     */
    private void registerClusterStateListener(final ClusterStateListener clusterStateListener) {
        OpenSearchResources.INSTANCE.getClusterService().addListener(clusterStateListener);
    }

    /**
     * Called when cluster state changes.
     *
     * @param event The cluster change event.
     */
    @Override
    public void clusterChanged(final ClusterChangedEvent event) {
        // Check if cluster state is set, if set, remove the listener and try to read cluster
        // settings.
        final ClusterState state = event.state();

        if (state != null) {
            OpenSearchResources.INSTANCE.getClusterService().removeListener(this);
            registerSettingUpdateListener();
            readAllManagedClusterSettings();
        }
    }

    /**
     * Calls all the listeners for the specified setting with the requested value.
     *
     * @param setting The setting whose listeners need to be notified.
     * @param settingValue The new value for the setting.
     */
    private void callIntSettingListeners(final Setting<Integer> setting, int settingValue) {
        try {
            final List<ClusterSettingListener<Integer>> listeners =
                    intSettingListenerMap.get(setting);
            if (listeners != null) {
                for (ClusterSettingListener<Integer> listener : listeners) {
                    listener.onSettingUpdate(settingValue);
                }
            }
        } catch (Exception ex) {
            LOG.error(ex);
            PerformanceAnalyzerApp.WRITER_METRICS_AGGREGATOR.updateStat(
                    WriterMetrics.OPENSEARCH_REQUEST_INTERCEPTOR_ERROR, "", 1);
        }
    }

    /**
     * Calls all the listeners for the specified setting with the requested value.
     *
     * @param setting The setting whose listeners need to be notified.
     * @param settingValue The new value for the setting.
     */
    private void callStringSettingListeners(final Setting<String> setting, String settingValue) {
        try {
            final List<ClusterSettingListener<String>> listeners =
                    stringSettingListenerMap.get(setting);
            if (listeners != null) {
                for (ClusterSettingListener<String> listener : listeners) {
                    listener.onSettingUpdate(settingValue);
                }
            }
        } catch (Exception ex) {
            LOG.error(ex);
            PerformanceAnalyzerApp.WRITER_METRICS_AGGREGATOR.updateStat(
                    WriterMetrics.OPENSEARCH_REQUEST_INTERCEPTOR_ERROR, "", 1);
        }
    }
    /** Class that handles response to GET /_cluster/settings */
    private class ClusterSettingsResponseHandler implements ActionListener<ClusterStateResponse> {
        /**
         * Handle action response. This response may constitute a failure or a success but it is up
         * to the listener to make that decision.
         *
         * @param clusterStateResponse
         */
        @Override
        public void onResponse(final ClusterStateResponse clusterStateResponse) {
            final Settings clusterSettings =
                    clusterStateResponse.getState().getMetadata().persistentSettings();

            for (final Setting<Integer> setting : managedIntSettings) {
                Integer settingValue = clusterSettings.getAsInt(setting.getKey(), null);
                if (settingValue != null) {
                    callIntSettingListeners(setting, settingValue);
                }
            }

            for (final Setting<String> setting : managedStringSettings) {
                String settingValue = clusterSettings.get(setting.getKey(), "");
                if (settingValue != null) {
                    callStringSettingListeners(setting, settingValue);
                }
            }
        }

        /**
         * A failure caused by an exception at some phase of the task.
         *
         * @param e
         */
        @Override
        public void onFailure(final Exception e) {
            LOG.error("Unable to read cluster settings. Exception: {}", e.getMessage());
            LOG.error(e);
        }
    }
}
