package edu.upenn.cis.nets212.news.livy;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.livy.LivyClient;
import org.apache.livy.LivyClientBuilder;

import edu.upenn.cis.nets212.config.Config;
import edu.upenn.cis.nets212.news.livy.MyPair;
import scala.Tuple2;
import edu.upenn.cis.nets212.news.livy.BuildGraphJob;

public class AdsorptionLivy {
	public static void main(String[] args) throws IOException, URISyntaxException, InterruptedException, ExecutionException {
		
		//fix!!
		LivyClient client = new LivyClientBuilder()
				  .setURI(new URI("http://ec2-3-84-113-117.compute-1.amazonaws.com:8998/"))
				  .build();

		try {
			String jar = "target/nets212-news-0.0.1-SNAPSHOT.jar";
			
			
		
			System.out.printf("Uploading %s to the Spark context...\n", jar);
			client.uploadJar(new File(jar)).get();
		  
//			String sourceFile = Config.ARTICLE_PATH;
			List<MyPair<String, List<List<Object>>>> result = client.submit(new BuildGraphJob()).get();
			
			//runs SocialRankJob to get the top ten without backlinks
//		 	List<MyPair<Integer,Double>> topTenWithout = client.submit(new SocialRankJob(false, sourceFile)).get();
			
			//creates sets for the nodes of the two runs, fills them
			//gets the interesection, then subtracts it out to get the ones that are exclusive
//		 	Set<Integer> withBack = new HashSet<Integer>();
//		 	Set<Integer> without = new HashSet<Integer>();
//		 	for (MyPair<Integer, Double> node : result) {
//				withBack.add(node.getLeft());
//		 	}
//		 	for (MyPair<Integer, Double> node : topTenWithout) {
//				without.add(node.getLeft());
//		  	}
//		    Set<Integer> intersection = new HashSet<Integer>(withBack);
//		  	intersection.retainAll(without);
//		  	withBack.removeAll(intersection);
//		  	without.removeAll(intersection);
		  
//		  	int interLength = intersection.toString().length();
//		  	int withLength = withBack.toString().length();
//		  	int withoutLength = without.toString().length();
		  
			//create a file to writ e the results to
			File f = new File("results.txt");
		  
			//rights the results to a file using a printwriter and buffered writer
			try (PrintWriter p = new PrintWriter(new BufferedWriter(new FileWriter(f, true)))) {
				
				//iterates through each node and writes the node and rank to the file
				for (MyPair<String, List<List<Object>>> node : result) {
					p.println(node.left);
					for (List<Object> l : node.right) {
						p.print(l);
					}
					p.println(node.toString());
				}	
			  
			} catch (Exception e) {
				e.printStackTrace();
			}
		  
		} finally {
		  client.stop(true);
		}
	}
}
