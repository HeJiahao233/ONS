/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ons.ra;

import ons.EONLightPath;
import ons.EONLink;
import ons.EONPhysicalTopology;
import ons.Flow;
import ons.LightPath;
import ons.Modulation;
import ons.Path;
import ons.util.KSPOffline;
import ons.util.WeightedGraph;
import java.util.ArrayList;
import java.util.TreeSet;

/**
 * @author lucas
 */
public class KSP_ProtectionDLP implements RA {

    private ControlPlaneForRA cp;
    private WeightedGraph graph;
    private int modulation;
    private KSPOffline routes;

    @Override
    public void simulationInterface(ControlPlaneForRA cp) {
        this.cp = cp;
        this.graph = cp.getPT().getWeightedGraph();
        this.modulation = Modulation._BPSK;
        int ksp = 40;
        this.routes = KSPOffline.getKSPOfflineObject(graph, ksp);
    }

    @Override
    public void setModulation(int modulation) {
        this.modulation = modulation;
    }

    @Override
    public void simulationEnd() {   
    }
    
    @Override
    public void flowArrival(Flow flow) {

        int[] nodes = null;
        int[] links = null;
        long id;
        LightPath[] lps = new LightPath[1];
        LightPath[] lpsBackup;
        Path[] primary = new Path[1], backup = new Path[1];

        // k-Shortest Paths routing
        ArrayList<Integer>[] kpaths = routes.getKShortestPaths(flow.getSource(), flow.getDestination());
        int k;
        boolean flag = false;
        for (k = 0; k < kpaths.length; k++) {

            nodes = route(kpaths, k);
            // If no possible path found, block the call
            if (nodes.length == 0 || nodes == null) {
                cp.blockFlow(flow.getID());
                return;
            }

            // Create the links vector
            links = new int[nodes.length - 1];
            for (int j = 0; j < nodes.length - 1; j++) {
                links[j] = cp.getPT().getLink(nodes[j], nodes[j + 1]).getID();
            }

            //Get the distance the size in KM  link on the route
            double largestLinkKM = 0;
            for (int i = 0; i < links.length; i++) {
                largestLinkKM = largestLinkKM + ((EONLink) cp.getPT().getLink(links[i])).getWeight();
            }
            //Adaptative modulation:
            int modulation = Modulation.getBestModulation(largestLinkKM);

            // First-Fit spectrum assignment in BPSK Modulation
            int requiredSlots = Modulation.convertRateToSlot(flow.getRate(), EONPhysicalTopology.getSlotSize(), modulation);

            int[] firstSlot;
            // Try the slots available in each link
            firstSlot = ((EONLink) cp.getPT().getLink(links[0])).firstFit(requiredSlots);
            for (int j = 0; j < firstSlot.length; j++) {
                // Now you create the lightpath to use the createLightpath VT
                //Relative index modulation: BPSK = 0; QPSK = 1; 8QAM = 2; 16QAM = 3;
                EONLightPath lp = cp.createCandidateEONLightPath(flow.getSource(), flow.getDestination(), links,
                        firstSlot[j], (firstSlot[j] + requiredSlots - 1), modulation);
                // Now you try to establish the new lightpath, accept the call
                if ((id = cp.getVT().createLightpath(lp)) >= 0) {
                    // Single-hop routing (end-to-end lightpath)
                    lps[0] = cp.getVT().getLightpath(id);
                    primary[0] = new Path(lps);
                    flag = true;
                    break;
                }
            }
            if (flag) {
                break;
            }
        }
        if (flag) {
            //Backup
            boolean flag2;
            if (nodes != null) {
                lpsBackup = new LightPath[nodes.length - 1];
                for (int i = 0; i < nodes.length - 1; i++) {
                    flag2 = false;
                    kpaths = routes.getKShortestPaths(nodes[i], nodes[i + 1]);
                    for (k = 0; k < kpaths.length; k++) {
                        int[] nodesBackup = route(kpaths, k);
                        // If no possible path found, block the call
                        if (nodesBackup.length == 0 || nodesBackup == null) {
                            if (flag) {
                                cp.getVT().deallocatedLightpaths(lps);
                            }
                            cp.blockFlow(flow.getID());
                            return;
                        }
                        // Create the links vector
                        int[] linksBackup = new int[nodesBackup.length - 1];
                        for (int j = 0; j < nodesBackup.length - 1; j++) {
                            linksBackup[j] = cp.getPT().getLink(nodesBackup[j], nodesBackup[j + 1]).getID();
                        }
                        int[] linkAux = new int[1];
                        linkAux[0] = cp.getPT().getLink(nodes[i], nodes[i + 1]).getID();
                        if (isDisjointed(linkAux, linksBackup)) {
                            double largestLinkKM = 0;
                            for (int j = 0; j < linksBackup.length; j++) {
                                largestLinkKM = largestLinkKM + ((EONLink) cp.getPT().getLink(linksBackup[j])).getWeight();
                            }
                            int modulation = Modulation.getBestModulation(largestLinkKM);
                            int requiredSlots = Modulation.convertRateToSlot(flow.getRate(), EONPhysicalTopology.getSlotSize(), modulation);

                            int[] firstSlot;
                            firstSlot = ((EONLink) cp.getPT().getLink(linksBackup[0])).firstFit(requiredSlots);
                            for (int j = 0; j < firstSlot.length; j++) {
                                EONLightPath lp = cp.createCandidateEONLightPath(nodes[i], nodes[i + 1], linksBackup,
                                        firstSlot[j], (firstSlot[j] + requiredSlots - 1), modulation);
                                if ((id = cp.getVT().createLightpath(lp)) >= 0) {
                                    lpsBackup[i] = cp.getVT().getLightpath(id);
                                    flag2 = true;
                                    break;
                                }
                            }
                        }
                        if (flag2) {
                            break;
                        }
                    }
                    if (!flag2) {
                        if (flag) {
                            cp.getVT().deallocatedLightpaths(lps);
                        }
                        // Block the call
                        cp.blockFlow(flow.getID());
                        return;
                    }
                }
                backup[0] = new Path(lpsBackup);
                int[] bws = {flow.getRate()};
                if (!cp.acceptFlow(flow.getID(), primary, bws, backup, bws, false, false)) {
                    //throw (new IllegalArgumentException());
                    cp.getVT().deallocatedLightpaths(lps);
                    cp.getVT().deallocatedLightpaths(lpsBackup);
                    cp.blockFlow(flow.getID());
                    return;
                } else {
                    return;
                }
            }
            //se nao conseguiu alocar o bakcup desaloca o primario
            cp.getVT().deallocatedLightpaths(lps);
        }
        // Block the call
        cp.blockFlow(flow.getID());
    }

    @Override
    public void flowDeparture(long id) {
    }

    private int[] route(ArrayList<Integer>[] kpaths, int k) {
        if (kpaths[k] != null) {
            int[] path = new int[kpaths[k].size()];
            for (int i = 0; i < path.length; i++) {
                path[i] = kpaths[k].get(i);
            }
            return path;
        } else {
            return null;
        }

    }

    private boolean isDisjointed(int[] links, int[] linksBackup) {
        for (int i = 0; i < links.length; i++) {
            for (int j = 0; j < linksBackup.length; j++) {
                if (links[i] == linksBackup[j]) {
                    return false;
                }
            }
        }
        return true;
    }

}
