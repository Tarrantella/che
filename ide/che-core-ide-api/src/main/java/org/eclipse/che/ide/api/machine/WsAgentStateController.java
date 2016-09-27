/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.ide.api.machine;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.web.bindery.event.shared.EventBus;

import org.eclipse.che.api.promises.client.Operation;
import org.eclipse.che.api.promises.client.OperationException;
import org.eclipse.che.api.promises.client.Promise;
import org.eclipse.che.api.promises.client.PromiseError;
import org.eclipse.che.api.promises.client.callback.AsyncPromiseHelper;
import org.eclipse.che.api.workspace.shared.dto.WsAgentHealthStateDto;
import org.eclipse.che.ide.api.dialogs.DialogFactory;
import org.eclipse.che.ide.api.machine.events.WsAgentStateEvent;
import org.eclipse.che.ide.rest.AsyncRequestFactory;
import org.eclipse.che.ide.rest.DtoUnmarshallerFactory;
import org.eclipse.che.ide.rest.RestContext;
import org.eclipse.che.ide.rest.RestServiceInfo;
import org.eclipse.che.ide.rest.StringUnmarshaller;
import org.eclipse.che.ide.rest.Unmarshallable;
import org.eclipse.che.ide.ui.loaders.LoaderPresenter;
import org.eclipse.che.ide.util.loging.Log;
import org.eclipse.che.ide.websocket.MessageBus;
import org.eclipse.che.ide.websocket.MessageBusProvider;
import org.eclipse.che.ide.websocket.events.ConnectionClosedHandler;
import org.eclipse.che.ide.websocket.events.ConnectionErrorHandler;
import org.eclipse.che.ide.websocket.events.ConnectionOpenedHandler;
import org.eclipse.che.ide.websocket.events.WebSocketClosedEvent;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.collect.Lists.newArrayList;
import static org.eclipse.che.api.core.model.workspace.WorkspaceStatus.RUNNING;
import static org.eclipse.che.ide.MimeType.APPLICATION_JSON;
import static org.eclipse.che.ide.api.machine.WsAgentState.STARTED;
import static org.eclipse.che.ide.api.machine.WsAgentState.STOPPED;
import static org.eclipse.che.ide.rest.HTTPHeader.ACCEPT;

/**
 * @author Roman Nikitenko
 * @author Valeriy Svydenko
 */
@Singleton
public class WsAgentStateController implements ConnectionOpenedHandler, ConnectionClosedHandler, ConnectionErrorHandler {
    private final EventBus               eventBus;
    private final MessageBusProvider     messageBusProvider;
    private final DtoUnmarshallerFactory dtoUnmarshallerFactory;
    private final DialogFactory          dialogFactory;
    private final String                 restContext;
    private final AsyncRequestFactory    asyncRequestFactory;
    private final LoaderPresenter        loader;
    //not used now added it for future if it we will have possibility check that service available for client call
    private final List<RestServiceInfo>  availableServices;
    private       DevMachine             devMachine;
    private       MessageBus             messageBus;
    private       WsAgentState           state;
    private List<AsyncCallback<MessageBus>> messageBusCallbacks = newArrayList();
    private List<AsyncCallback<DevMachine>> devMachineCallbacks = newArrayList();

    @Inject
    public WsAgentStateController(@RestContext String restContext,
                                  EventBus eventBus,
                                  LoaderPresenter loader,
                                  MessageBusProvider messageBusProvider,
                                  AsyncRequestFactory asyncRequestFactory,
                                  DtoUnmarshallerFactory dtoUnmarshallerFactory,
                                  DialogFactory dialogFactory) {
        this.restContext = restContext;
        this.loader = loader;
        this.eventBus = eventBus;
        this.messageBusProvider = messageBusProvider;
        this.asyncRequestFactory = asyncRequestFactory;
        this.dtoUnmarshallerFactory = dtoUnmarshallerFactory;
        this.dialogFactory = dialogFactory;
        this.availableServices = new ArrayList<>();
    }

    public void initialize(DevMachine devMachine) {
        this.devMachine = devMachine;
        this.state = STOPPED;
        loader.show(LoaderPresenter.Phase.STARTING_WORKSPACE_AGENT);
        checkHttpConnection();
    }

    @Override
    public void onClose(WebSocketClosedEvent event) {
        if (state.equals(STARTED)) {
            checkWsAgentState();
        }
    }

    private void checkWsAgentState() {
        final String url = restContext + "/workspace/" + devMachine.getWorkspace() + "/check";
        final Unmarshallable<WsAgentHealthStateDto> unmarshaller = dtoUnmarshallerFactory.newUnmarshaller(WsAgentHealthStateDto.class);
        final Promise<WsAgentHealthStateDto> wsAgentState = asyncRequestFactory.createGetRequest(url)
                                                                               .header(ACCEPT, APPLICATION_JSON)
                                                                               .send(unmarshaller);
        wsAgentState.then(new Operation<WsAgentHealthStateDto>() {
            @Override
            public void apply(WsAgentHealthStateDto arg) throws OperationException {
                if (RUNNING.equals(arg.getWorkspaceStatus())) {
                    checkStateOfWsAgent(arg);
                }
            }
        }).catchError(new Operation<PromiseError>() {
            @Override
            public void apply(PromiseError arg) throws OperationException {
                Log.error(getClass(), arg.getMessage());
            }
        });
    }

    @Override
    public void onError() {
        if (state.equals(STARTED)) {
            state = STOPPED;
            eventBus.fireEvent(WsAgentStateEvent.createWsAgentStoppedEvent());
        }
    }

    @Override
    public void onOpen() {
        messageBus.removeOnOpenHandler(this);
        //need to make sure ready state equals 1 (OPEN) in same situations after opening it still equals 0 (CONNECTING)
        new Timer() {
            @Override
            public void run() {
                if (messageBus.getReadyState().equals(MessageBus.ReadyState.OPEN)) {
                    cancel();
                    started();
                }
            }
        }.scheduleRepeating(300);
    }

    private void started() {
        state = STARTED;
        loader.setSuccess(LoaderPresenter.Phase.STARTING_WORKSPACE_AGENT);

        for (AsyncCallback<MessageBus> callback : messageBusCallbacks) {
            callback.onSuccess(messageBus);
        }
        messageBusCallbacks.clear();

        for (AsyncCallback<DevMachine> callback : devMachineCallbacks) {
            callback.onSuccess(devMachine);
        }
        devMachineCallbacks.clear();

        eventBus.fireEvent(WsAgentStateEvent.createWsAgentStartedEvent());
    }

    public WsAgentState getState() {
        return state;
    }

    public Promise<MessageBus> getMessageBus() {
        return AsyncPromiseHelper.createFromAsyncRequest(new AsyncPromiseHelper.RequestCall<MessageBus>() {
            @Override
            public void makeCall(AsyncCallback<MessageBus> callback) {
                if (messageBus != null) {
                    callback.onSuccess(messageBus);
                } else {
                    WsAgentStateController.this.messageBusCallbacks.add(callback);
                }
            }
        });
    }

    /**
     * Goto checking HTTP connection via getting all registered REST Services
     */
    private void checkHttpConnection() {
        //here we add trailing slash because {@link org.eclipse.che.api.core.rest.ApiInfoService} mapped in this way
        String url = devMachine.getWsAgentBaseUrl() + '/';
        final String pingUrl = restContext + "/workspace/" + devMachine.getWorkspace() + "/check";
        asyncRequestFactory.createGetRequest(url).send(new StringUnmarshaller()).then(new Operation<String>() {
            @Override
            public void apply(String result) throws OperationException {
                try {
                    JSONObject object = JSONParser.parseStrict(result).isObject();
                    if (object.containsKey("rootResources")) {
                        JSONArray rootResources = object.get("rootResources").isArray();
                        for (int i = 0; i < rootResources.size(); i++) {
                            JSONObject rootResource = rootResources.get(i).isObject();
                            String regex = rootResource.get("regex").isString().stringValue();
                            String fqn = rootResource.get("fqn").isString().stringValue();
                            String path = rootResource.get("path").isString().stringValue();
                            availableServices.add(new RestServiceInfo(fqn, regex, path));
                        }
                    }
                } catch (Exception exception) {
                    Log.warn(getClass(), "Parse root resources failed.");
                }

                checkWsConnection();
            }
        }).catchError(new Operation<PromiseError>() {
            @Override
            public void apply(PromiseError arg) throws OperationException {
                asyncRequestFactory.createGetRequest(pingUrl)
                                   .header(ACCEPT, APPLICATION_JSON)
                                   .send(dtoUnmarshallerFactory.newUnmarshaller(WsAgentHealthStateDto.class))
                                   .then(new Operation<WsAgentHealthStateDto>() {
                                       @Override
                                       public void apply(WsAgentHealthStateDto arg) throws OperationException {
                                           if (RUNNING.equals(arg.getWorkspaceStatus())) {
                                               checkStateOfWsAgent(arg);
                                           }
                                       }
                                   }).catchError(new Operation<PromiseError>() {
                    @Override
                    public void apply(PromiseError arg) throws OperationException {
                        Log.error(getClass(), arg.getMessage());
                    }
                });
            }
        });
    }

    private void checkStateOfWsAgent(WsAgentHealthStateDto agentHealthStateDto) {
        final int statusCode = agentHealthStateDto.getCode();

        String infoWindowTitle = "Workspace Agent";

        if (statusCode == 200) {
            dialogFactory.createMessageDialog(infoWindowTitle,
                                              "Your workspace is not responding. To fix the problem, verify you have a good " +
                                              "network connection and restart the workspace.",
                                              null).show();
        } else {
            dialogFactory.createMessageDialog(infoWindowTitle,
                                              "Your workspace has stopped responding. To fix the problem, " +
                                              "restart the workspace in the dashboard.",
                                              null).show();
        }
    }

    /**
     * Try to connect via WebSocket connection
     */
    private void checkWsConnection() {
        if (messageBus != null) {
            messageBus.cancelReconnection();
        }
        messageBus = messageBusProvider.createMachineMessageBus(devMachine.getWsAgentWebSocketUrl());
        messageBus.addOnCloseHandler(this);
        messageBus.addOnErrorHandler(this);
        messageBus.addOnOpenHandler(this);
    }

}
