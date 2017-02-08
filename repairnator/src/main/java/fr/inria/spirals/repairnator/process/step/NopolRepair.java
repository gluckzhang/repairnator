package fr.inria.spirals.repairnator.process.step;

import com.sun.xml.internal.org.jvnet.mimepull.MIMEConfig;
import fr.inria.lille.commons.synthesis.smt.solver.SolverFactory;
import fr.inria.lille.repair.ProjectReference;
import fr.inria.lille.repair.common.config.Config;
import fr.inria.lille.repair.common.patch.Patch;
import fr.inria.lille.repair.common.synth.StatementType;
import fr.inria.lille.repair.nopol.NoPol;
import fr.inria.spirals.repairnator.process.ProjectInspector;
import fr.inria.spirals.repairnator.process.ProjectState;
import fr.inria.spirals.repairnator.process.maven.MavenHelper;
import fr.inria.spirals.repairnator.process.testinformation.ComparatorFailureLocation;
import fr.inria.spirals.repairnator.process.testinformation.FailureLocation;
import fr.inria.spirals.repairnator.process.testinformation.FailureType;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelBuildingException;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Created by urli on 05/01/2017.
 */
public class NopolRepair extends AbstractStep {
    private static final int TOTAL_MAX_TIME = 4*60; // We expect it to run 4 hours top.
    private static final int MIN_TIMEOUT = 2;

    private Map<String,List<Patch>> patches;


    public NopolRepair(ProjectInspector inspector) {
        super(inspector);
    }

    public Map<String,List<Patch>> getPatches() {
        return patches;
    }

    @Override
    protected void businessExecute() {
        this.getLogger().debug("Start to use nopol to repair...");
        List<URL> classPath = this.inspector.getRepairClassPath();
        File[] sources = this.inspector.getRepairSourceDir();

        GatherTestInformation infoStep = inspector.getTestInformations();
        List<FailureLocation> failureLocationList = new ArrayList<>(infoStep.getFailureLocations());
        Collections.sort(failureLocationList, new ComparatorFailureLocation());

        this.patches = new HashMap<String,List<Patch>>();
        boolean patchCreated = false;

        int allocatedTimeForFails, allocatedTimeForErrors;

        allocatedTimeForErrors = TOTAL_MAX_TIME/(infoStep.getNbFailingTests()*2+infoStep.getNbErroringTests());
        if (allocatedTimeForErrors < MIN_TIMEOUT) {
            allocatedTimeForErrors = MIN_TIMEOUT;
        }
        allocatedTimeForFails = allocatedTimeForErrors*2;



        for (FailureLocation failureLocation : failureLocationList) {
            String testClass = failureLocation.getClassName();
            int timeout = (failureLocation.isError()) ? allocatedTimeForErrors : allocatedTimeForFails;

            this.getLogger().debug("Launching repair with Nopol for following test class: "+testClass+" (should timeout in "+timeout+" minutes");

            ProjectReference projectReference = new ProjectReference(sources, classPath.toArray(new URL[classPath.size()]), new String[] {testClass});
            Config config = new Config();
            config.setComplianceLevel(8);
            config.setTimeoutTestExecution(60);
            config.setMaxTimeInMinutes(timeout);
            config.setLocalizer(Config.NopolLocalizer.GZOLTAR);
            config.setSolverPath(this.inspector.getNopolSolverPath());
            config.setSynthesis(Config.NopolSynthesis.DYNAMOTH);
            config.setType(StatementType.PRE_THEN_COND);

            SolverFactory.setSolver(config.getSolver(), config.getSolverPath());

            final NoPol nopol = new NoPol(projectReference, config);
            List<Patch> patch = null;

            final ExecutorService executor = Executors.newSingleThreadExecutor();
            final Future nopolExecution = executor.submit(
                    new Callable() {
                        @Override
                        public Object call() throws Exception {
                            return nopol.build(projectReference.testClasses());
                        }
                    });

            try {
                executor.shutdown();
                patch = (List<Patch>) nopolExecution.get(config.getMaxTimeInMinutes(), TimeUnit.MINUTES);
            } catch (TimeoutException exception) {
                this.addStepError("Timeout: execution time > " + config.getMaxTimeInMinutes() + " " + TimeUnit.MINUTES);
            } catch (InterruptedException | ExecutionException e) {
                this.addStepError(e.getMessage());
                continue;
            }

            nopolExecution.cancel(true);

            if (patch != null && !patch.isEmpty()) {
                this.patches.put(testClass, patch);
                patchCreated = true;
            }
        }

        if (!patchCreated) {
            this.addStepError("No patch has been generated by Nopol. Look at the trace to get more information.");
            return;
        }
        this.setState(ProjectState.PATCHED);

    }


}
