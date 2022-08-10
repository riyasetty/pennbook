package edu.upenn.cis.nets212.config;

/**
 * Global configuration for NETS 212 homeworks.
 * 
 * A better version of this would read a config file from the resources,
 * such as a YAML file.  But our first version is designed to be simple
 * and minimal. 
 * 
 * @author zives
 *
 */
public class Config {

	/**
	 * If we set up a local DynamoDB server, where does it listen?
	 */
	public static int DYNAMODB_LOCAL_PORT = 8000;

	/**
	 * This is the connection to the DynamoDB server, which defaults
	 * to local...
	 */
	public static String DYNAMODB_URL = //"http://localhost:8000";
			"https://dynamodb.us-east-1.amazonaws.com";
	
	/**
	 * Do we want to use the local DynamoDB instance or a remote one?
	 * 
	 * If we are local, performance is really slow - so you should switch
	 * to the real thing as soon as basic functionality is in place.
	 */
	public static Boolean LOCAL_DB = false;
	
	//public static String TED_TALK_PATH = "target/ted_talks_en.csv";
	public static String ARTICLE_PATH = "News_Category_Dataset_v2.json";

	public static String STOP_WORDS = "src/main/resources/nlp_en_stop_words.txt";
	
	/**
	 * How many RDD partitions to use?
	 */
	public static int PARTITIONS = 5;
}
