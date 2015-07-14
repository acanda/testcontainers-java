package org.rnorth.testcontainers.containers;

import com.spotify.docker.client.*;
import com.spotify.docker.client.messages.*;
import org.rnorth.testcontainers.utility.PathOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Executors;

import static org.rnorth.testcontainers.utility.CommandLine.runShellCommand;

/**
 * Base class for that allows a container to be launched and controlled.
 */
public abstract class AbstractContainer {

    private static final Logger LOGGER = LoggerFactory.getLogger("TestContainer");

    protected String dockerHostIpAddress;
    protected String containerId;
    private String containerName;
    protected DockerClient dockerClient;
    protected String tag = "latest";
    private boolean normalTermination = false;

    /**
     * Starts the container using docker, pulling an image if necessary.
     */
    public void start() {

        LOGGER.debug("Start for container ({}): {}", getDockerImageName(), this);

        try {

            dockerClient = customizeBuilderForOs().build();

            pullImageIfNeeded(getDockerImageName());

            ContainerConfig containerConfig = getContainerConfig();

            HostConfig.Builder hostConfigBuilder = HostConfig.builder()
                    .publishAllPorts(true);
            customizeHostConfigBuilder(hostConfigBuilder);
            HostConfig hostConfig = hostConfigBuilder.build();

            LOGGER.info("Creating container for image: {}", getDockerImageName());
            ContainerCreation containerCreation = dockerClient.createContainer(containerConfig);

            containerId = containerCreation.id();
            dockerClient.startContainer(containerId, hostConfig);
            LOGGER.info("Starting container with ID: {}", containerId);

            ContainerInfo containerInfo = dockerClient.inspectContainer(containerId);
            containerName = containerInfo.name();

            containerIsStarting(containerInfo);

            waitUntilContainerStarted();
            LOGGER.info("Container started");

            // If the container stops before the after() method, its termination was unexpected
            Executors.newSingleThreadExecutor().submit(new Runnable() {
                @Override
                public void run() {
                    Exception caughtException = null;
                    try {
                        dockerClient.waitContainer(containerId);
                    } catch (DockerException | InterruptedException e) {
                        caughtException = e;
                    }

                    if (!normalTermination) {
                        throw new RuntimeException("Container exited unexpectedly", caughtException);
                    }
                }
            });

            // If the JVM stops without the container being stopped, try and stop the container
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    LOGGER.debug("Hit shutdown hook for container {}", AbstractContainer.this.containerId);
                    AbstractContainer.this.stop();
                }
            }));
        } catch (Exception e) {
            LOGGER.error("Could not start container", e);

            throw new ContainerLaunchException("Could not create/start container", e);
        }
    }

    /**
     * Allows subclasses to apply additional configuration to the HostConfig.Builder prior to container creation.
     *
     * @param hostConfigBuilder
     */
    protected void customizeHostConfigBuilder(HostConfig.Builder hostConfigBuilder) {

    }

    private void pullImageIfNeeded(final String imageName) throws DockerException, InterruptedException {
        List<Image> images = dockerClient.listImages(DockerClient.ListImagesParam.create("name", getDockerImageName()));
        for (Image image : images) {
            if (image.repoTags().contains(imageName)) {
                // the image exists
                return;
            }
        }

        LOGGER.info("Pulling docker image: {}. Please be patient; this may take some time but only needs to be done once.", imageName);
        dockerClient.pull(getDockerImageName(), new ProgressHandler() {
            @Override
            public void progress(ProgressMessage message) throws DockerException {
                if (message.error() != null) {
                    if (message.error().contains("404") || message.error().contains("not found")) {
                        throw new ImageNotFoundException(imageName, message.toString());
                    } else {
                        throw new ImagePullFailedException(imageName, message.toString());
                    }
                }
            }
        });
    }

    /**
     * Stops the container.
     */
    public void stop() {

        LOGGER.debug("Stop for container ({}): {}", getDockerImageName(), this);

        try {
            LOGGER.info("Stopping container: {}", containerId);
            normalTermination = true;
            dockerClient.killContainer(containerId);
            dockerClient.removeContainer(containerId, true);
        } catch (DockerException | InterruptedException e) {
            LOGGER.debug("Error encountered shutting down container (ID: {}) - it may not have been stopped, or may already be stopped: {}", containerId, e.getMessage());
        }
    }

    /**
     * Creates a directory on the local filesystem which will be mounted as a volume for the container.
     *
     * @param temporary is the volume directory temporary? If true, the directory will be deleted on JVM shutdown.
     * @return path to the volume directory
     * @throws IOException
     */
    protected Path createVolumeDirectory(boolean temporary) throws IOException {
        File file = new File(".tmp-volume-" + System.currentTimeMillis());
        file.mkdirs();
        final Path directory = file.toPath();

        if (temporary) Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                PathOperations.recursiveDeleteDir(directory);
            }
        }));

        return directory;
    }

    /**
     * Hook to notify subclasses that the container is starting
     *
     * @param containerInfo
     */
    protected abstract void containerIsStarting(ContainerInfo containerInfo);

    /**
     * @return a port number (specified as a String) which the contained application will listen on when alive. If a subclass does not need a liveness check this should just return null
     */
    protected abstract String getLivenessCheckPort();

    /**
     * @return container configuration
     */
    protected abstract ContainerConfig getContainerConfig();

    /**
     * @return the docker image name
     */
    protected abstract String getDockerImageName();

    /**
     * Wait until the container has started. The default implementation simply
     * waits for a port to start listening; subclasses may override if more
     * sophisticated behaviour is required.
     */
    protected void waitUntilContainerStarted() {
        waitForListeningPort(dockerHostIpAddress, getLivenessCheckPort());
    }

    /**
     * Waits for a port to start listening for incoming connections.
     *
     * @param ipAddress the IP address to attempt to connect to
     * @param port      the port which will start accepting connections
     */
    protected void waitForListeningPort(String ipAddress, String port) {

        if (port == null) {
            return;
        }

        for (int i = 0; i < 6000; i++) {
            try {
                new Socket(ipAddress, Integer.valueOf(port));
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException ignored) {
                }
            }
        }
        throw new IllegalStateException("Timed out waiting for container port to open (" + ipAddress + ":" + port + " should be listening)");
    }

    private DefaultDockerClient.Builder customizeBuilderForOs() throws Exception {
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {

            DefaultDockerClient.Builder builder = DefaultDockerClient.builder();

            // Running on a Mac therefore use boot2docker
            runShellCommand("/usr/local/bin/boot2docker", "up");
            dockerHostIpAddress = runShellCommand("/usr/local/bin/boot2docker", "ip");

            builder.uri("https://" + dockerHostIpAddress + ":2376")
                    .dockerCertificates(new DockerCertificates(Paths.get(System.getProperty("user.home") + "/.boot2docker/certs/boot2docker-vm")));

            return builder;
        } else {
            dockerHostIpAddress = "127.0.0.1";
            return DefaultDockerClient.fromEnv();
        }
    }

    public void setTag(String tag) {
        this.tag = tag != null ? tag : "latest";
    }

    public String getContainerName() {
        return containerName;
    }
}
