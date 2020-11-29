/*
 * Copyright (C) 2020 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.core.test.deployment;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.exec.StartedProcess;

import java.io.FileReader;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertTrue;

public class AptTest {
    private static final Logger LOG = LoggerFactory.getLogger(AptTest.class);
    private static final String aptSnapshot = "https://repo.grakn.ai/repository/apt-snapshot/";
    private static final String aptRelease = "https://repo.grakn.ai/repository/apt/";
    private static final String pubkey1 = "8F3DA4B5E9AEF44C";
    private static final String pubkey2 = "https://cli-assets.heroku.com/apt/release.key";
    private static final String pubkey3 = "https://dl.google.com/linux/linux_signing_key.pub";
    private static final Path versionFile = Paths.get("VERSION");
    private static final int graknPort = 1729;

    private final String commit;
    private final ProcessExecutor executor;
    private StartedProcess graknProcess;
    private JsonObject workspaceRefs;

    public AptTest() throws IOException {
        commit = System.getenv("GRABL_COMMIT");
        executor = new ProcessExecutor().directory(Paths.get(".").toFile()).readOutput(true);
        workspaceRefs = Json.parse(new FileReader("./external/graknlabs_grakn_core_workspace_refs/refs.json")).asObject();
    }

    @Test
    public void test() throws InterruptedException, IOException, TimeoutException {
        setup();
        install();
        start();
        stop();
    }

    private void setup() throws InterruptedException, TimeoutException, IOException {
        execute("sudo", "apt-key", "adv", "--keyserver", "keyserver.ubuntu.com", "--recv", pubkey1);
        execute("sudo", "add-apt-repository", "deb [ arch=all ] " + aptSnapshot + " trusty main");
        execute("sudo", "add-apt-repository", "deb [ arch=all ] " + aptRelease + " trusty main");
        execute("bash", "-c", "curl " + pubkey2 + " | sudo apt-key add -");
        execute("bash", "-c", "wget -q -O - " + pubkey3 + " | sudo apt-key add -");
        execute("sudo", "apt", "update");
    }

    private void install() throws InterruptedException, TimeoutException, IOException {
        System.out.println("core = " + commit);
        Files.write(versionFile, commit.getBytes(StandardCharsets.US_ASCII));
        execute("sudo", "apt", "install", "-y", "grakn-core-server=0.0.0-" + commit, "grakn-bin=" + getDependencyVersion("graknlabs_common"));
    }

    private void start() throws InterruptedException, IOException {
        graknProcess = executor.command("grakn", "server").start();

        waitUntilReady();
        assertTrue("Grakn Core failed to start", graknProcess.getProcess().isAlive());

        System.out.println("Grakn Core database server started");
    }

    private void stop() {
        if (graknProcess != null) {
            try {
                System.out.println("Stopping Grakn Core database server");

                graknProcess.getProcess().destroy();

                System.out.println("Grakn Core database server stopped");
            } catch (Exception e) {
                throw e;
            }
        }
    }

    private void waitUntilReady() throws InterruptedException {
        int attempt = 0;
        while (!isGraknServerReady() && attempt < 25) {
            Thread.sleep(1000);
            attempt++;
        }
    }

    private static boolean isGraknServerReady() {
        try {
            Socket s = new Socket("localhost", graknPort);
            s.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String getDependencyVersion(String dependency) {
        String commitDep = workspaceRefs.get("commits").asObject().getString(dependency, null);

        if (commitDep != null) {
            return "0.0.0-" + commitDep;
        }

        String tagDep = workspaceRefs.get("tags").asObject().getString(dependency, null);
        if (tagDep != null) {
            return tagDep;
        }

        throw new RuntimeException(String.format("dependency %s not found", dependency));
    }

    private ProcessResult execute(String... cmd) throws InterruptedException, TimeoutException, IOException {
        ProcessResult result = executor.command(cmd).execute();
        if (result.getExitValue() != 0) {
            LOG.error("An error has occurred.");
            LOG.error(" - cmd: " + Arrays.toString(cmd));
            LOG.error(" - output: " + result.outputString());
            throw new RuntimeException();
        } else return result;
    }
}
