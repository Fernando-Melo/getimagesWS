package pt.archive.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;
import pt.archive.model.ImageSearchResult;
import pt.archive.model.ImageSearchResults;
import pt.archive.model.ItemOpenSearch;
import pt.archive.utils.Constants;
import pt.archive.utils.HTMLParser;
import pt.archive.utils.UserHandler;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.Arrays;

@Configuration
@RestController
public class ImageSearchResultsController {
	
	private final Logger log = LoggerFactory.getLogger( this.getClass( ) ); //Define the logger object for this class
	private List< String > terms;
	private String startIndex;
	private List< String > blacklListUrls;
	private List< String > blackListDomain;
	
	/** Properties file application.properties**/
	@Value( "${urlBase}" )
	private String urlBase;
	
	@Value( "${type}" )
	private String type;
	
	@Value( "${hitsPerSite}" )
	private String hitsPerSite;
	
	@Value( "${NumImgsbyUrl}" )
	private int numImgsbyUrl;
	
	@Value( "${hostGetImage}" )
	private String hostGetImage;
	
	@Value( "${urldirectoriesImage}" )
	private String urldirectoriesImage;
	
	@Value( "${hitsPerPage}" )
	private String hitsPerPage;
	
	@Value( "${NThreads}" )
	private int NThreads;
	
	@Value( "${TimeoutThreads}" )
	private long timeout;
	
	@Value( "${blacklistUrl.file}" )
	private String blackListUrlFileLocation;
	
	@Value( "${blacklistDomain.file}" )
	private String blacklistDomainFileLocation;
	
	@Value( "${urlBaseCDX}" )
	private String urlBaseCDX;
	
	@Value( "${outputCDX}" )
	private String outputCDX;
	
	@Value( "${flCDX}" )
	private String flParam;
	/***************************/
	
	private List< ItemOpenSearch > resultOpenSearch;
	
	@PostConstruct
	public void initIt( ) throws Exception {
	  log.info("Init method after properties are set : blacklistUrlFile[" + blackListUrlFileLocation +"] & blacklistDomainFile[" + blacklistDomainFileLocation + "]");
	  loadBlackListFiles( );
	  printProperties( );
	}
	
	/**
	 * @param query: full-text element
	 * @param startData: 
	 * @param endData
	 * @return 
	 */
    @RequestMapping(value = "/", method = RequestMethod.GET)
    public ImageSearchResults getImages( @RequestParam(value="query", defaultValue="") String query,
    									 @RequestParam(value="stamp", defaultValue="19960101000000-20151022163016") String stamtp,
    									 @RequestParam(value="start", defaultValue="0") String _startIndex ) {
    	log.info( "New request query[" + query + "] stamp["+ stamtp +"]" );
    	startIndex = _startIndex;
    	List< ImageSearchResult > imageResults = getImageResults( query , stamtp ); 
    	log.info( "Results = " + imageResults.size( ) );
    	return new ImageSearchResults( imageResults , imageResults.size( ) );
    }
    
    /* Method that calls the OpenSearchAPI to get the first N urls of the query
    * and Returns a list of Images
    */
    public List<ImageSearchResult> getImageResults( String query , String stamp ) {
    	String url;
    	ExecutorService pool = Executors.newFixedThreadPool( NThreads );
    	CountDownLatch doneSignal;
    	List< ImageSearchResult > imageResults 	= new ArrayList< >( );
    	List< ImageSearchResult > resultImages 	= new ArrayList< >( );
    	boolean isAllDone = false;
    	
    	if( query == null || query.trim( ).equals( "" ) ) {
 			log.warn("[ImageSearchResultsController][getImageResults] Query empty!");
 			imageResults.add( getErrorCode( "-1: query empty" ) ); 
 			return Collections.emptyList( );
 		}
 		
 		try {
 			cleanUpMemory( );
 			terms = new LinkedList< String >( Arrays.asList( query.split( " " ) ) );
 			prepareTerms( );
 			url = buildURL( query , stamp );
 			log.debug( "Teste input == " + URLEncoder.encode( query , "UTF-8" ).replace( "+" , "%20" ) + " url == " + url );
	 		// the SAX parser
 			UserHandler userhandler = new UserHandler( );
	 		XMLReader myReader = XMLReaderFactory.createXMLReader( );
	 		myReader.setContentHandler( userhandler );
	 		myReader.parse( new InputSource(new URL( url ).openStream( ) ) );
	 		resultOpenSearch = userhandler.getItems( );
	 		
	 		if( resultOpenSearch == null || resultOpenSearch.size( ) == 0 )  //No results the OpenSearch
	 			return  Collections.emptyList( );
	 		
	 		log.info( "[ImageSearchResultsController][getImageResults] OpenSearch result : " + resultOpenSearch.size( ) );
	 		doneSignal = new CountDownLatch( resultOpenSearch.size( ) );
	 		
	 		List< Future< List< ImageSearchResult > > > submittedJobs = new ArrayList< >( );
	 		for( ItemOpenSearch item : resultOpenSearch ) { //Search information tag <img>
 				Future< List< ImageSearchResult > > job = pool.submit( new HTMLParser( doneSignal , item,  numImgsbyUrl , hostGetImage , urldirectoriesImage , terms , urlBaseCDX, outputCDX, flParam , blacklListUrls , blackListDomain ) );
	 			submittedJobs.add( job );
	 		}
	 		try {
	 			isAllDone = doneSignal.await( timeout , TimeUnit.SECONDS );
	 		    if ( !isAllDone ) 
	            	cleanUpThreads( submittedJobs );
	        } catch ( InterruptedException e1 ) {
	        	cleanUpThreads( submittedJobs ); // take care, or cleanup
	        }
	 		
	 		//get images result to search
	 		for( Future< List< ImageSearchResult > >  job : submittedJobs ) {
	 			try {
	                if ( !isAllDone && !job.isDone( ) ) {  // before doing a get you may check if it is done
	                    job.cancel( true ); // cancel job and continue with others
	                    continue;
	                }
	    			List< ImageSearchResult > result = job.get( ); // wait for a processor to complete
		 			if( result != null && !result.isEmpty( ) ) {
		 				log.debug( "Resultados do future = " + result.size( ) );
		 				imageResults.addAll( result );
		 			}
	            } catch ( ExecutionException cause ) {
	            	log.error( "[ImageSearchResultsController][getImageResults]", cause ); // exceptions occurred during execution, in any
	            } catch ( InterruptedException e ) {
	            	log.error( "[ImageSearchResultsController][getImageResults]", e ); // take care
	            }
	 		}
	 		
	 		Collections.sort( imageResults ); //sort 
	 		log.info( "Numero de resposta com duplicados: " + imageResults.size( ) );
	 		resultImages = uniqueResult( imageResults ); //remove duplicates
	 		log.info( "Numero de resposta sem duplicados: " + resultImages.size( ) );
	 		//CDXParser parseCDX = new CDXParser( urlBaseCDX, outputCDX, flParam, imageResults ); //SORT
	 		//resultImages = parseCDX.getuniqueResults( );
	 		
	 		log.debug( "Request query[" + query + "] stamp["+ stamp +"] Number of results["+ resultImages.size( ) +"]" );
	 		
		} catch( UnsupportedEncodingException e2 ) {
 			log.error( "[ImageSearchResultsController][getImageResults]", e2 );
 		} catch( SAXException e3 ) {
 			log.error( "[ImageSearchResultsController][getImageResults]", e3 );
 		} catch( MalformedURLException e4 ) {
 			log.error( "[ImageSearchResultsController][getImageResults]", e4 );
 		} catch( IOException e5 ) {
 			log.error( "[ImageSearchResultsController][getImageResults]", e5 );
 		}catch( Exception e6 ) {
 			log.error( "[ImageSearchResultsController][getImageResults]", e6 );
 		}finally{
	 		if( pool != null )
	 			pool.shutdown( ); //shut down the executor service now
 		}
 		
 		return resultImages;
    }
    
    
    private String buildURL( String input , String stamp ) throws UnsupportedEncodingException {
    	return urlBase
    			.concat(  URLEncoder.encode( input , "UTF-8").replace("+", "%20") )
    			.concat( Constants.inOP )
    			.concat( "type" )
    			.concat( Constants.colonOP )
    			.concat( type )
    			.concat( Constants.inOP )
    			.concat( "date" )
    			.concat( Constants.colonOP )
    			.concat( stamp )
    			.concat( Constants.andOP )
    			.concat( "hitsPerSite" )
    			.concat( Constants.equalOP )
    			.concat( hitsPerSite )
    			.concat( Constants.andOP )
    			.concat( "hitsPerPage" )
    			.concat( Constants.equalOP )
    			.concat( hitsPerPage )
    			.concat( Constants.andOP )
    			.concat( "start" )
    			.concat( Constants.equalOP )
    			.concat( startIndex );
    }
    
    private List< ImageSearchResult > uniqueResult( List< ImageSearchResult > imageResults ) {
    	List< ImageSearchResult > uniqueList = new ArrayList< >( );
    	Set< ImageSearchResult > uniqueSet = new HashSet< >( );
    	for( ImageSearchResult obj : imageResults ) {
    		if( uniqueSet.add( obj ) ){
    			uniqueList.add( obj );
    		} 
    	}
    	return uniqueList;
    }
    
    private ImageSearchResult getErrorCode( String errorCode ) {
    	ImageSearchResult result = new ImageSearchResult( );
    	result.setUrl( errorCode );
    	return result;
    }
    
    private void cleanUpThreads( List< Future< List< ImageSearchResult > > > submittedJobs ) {
    	for ( Future< List< ImageSearchResult > > job : submittedJobs ) {
            job.cancel(true);
        }
    }
    
    private void prepareTerms( ){
    	removeStopWords( );
    	removeCharactersAdvancedSearch( );
    }
    
    private void removeCharactersAdvancedSearch( ){
    	int index = 0;
    	char checkquotes = 45,
    			checkaddElement = 45;
    	List< String > resultTerms = new ArrayList< >( );
    	String strTerm;
    	for( String term : terms ) {
    		if( checkquotes == 46 ) { //quotation marks start and not end
    			if( !term.contains( Constants.quotationMarks ) ) { //if no contain quotaiton marks
    				//TODO refazer com regex...
    			}
    		} else {
    			if( term.startsWith( Constants.quotationMarks ) )  {
        			//terms.set( index , terms.get( index ).substring( 1 ) );
        			strTerm = term.substring( 1 );
        			checkquotes = 46;
        		} 
        		
        		
        		if( term.endsWith( Constants.quotationMarks ) )
        			strTerm = term.substring( 0 , term.length( ) - 1 ) );
        		
    		}
    		if( checkaddElement == 45 )
    			resultTerms.add( strTerm );
    		strTerm = "";
    		index++;
    	}
    }
    
    private void removeStopWords( ) {
    	for( Iterator< String > iterator = terms.iterator( ) ; iterator.hasNext( ); ) {
    		String term = iterator.next( );
    		for( String stopWord : Constants.stopWord ) {
    			if( term.equals( stopWord ) ) {
    				log.info( "[StopWords] Remove term["+term+"] to ranking" );
    				iterator.remove( );
    			}
    		}
    	}
    }
    
    private void cleanUpMemory( ) {
		if( terms != null ) {
			log.info( "[DEBUGGG] imageResults["+ terms.size( ) +"] ");
			terms.clear( );
		}
		
		if( resultOpenSearch != null ) {
			log.info( "[DEBUGGG] resultOpenSearch["+ resultOpenSearch.size( ) +"] ");
			resultOpenSearch.clear( );
		}
    }
    
    private void loadBlackListFiles( ) {
    	loadBlackListUrls( );
    	loadBlackListDomain( );
    	
    }
    
    private void loadBlackListUrls( ) {
    	Scanner s = null;
    	try{
    		s = new Scanner( new File( blackListUrlFileLocation ) );
    		blacklListUrls = new ArrayList< String >( );
    		while( s.hasNext( ) ) {
    			blacklListUrls.add( s.next( ) );
    		}
    	} catch( IOException e ) {
    		log.error( "Load blacklist file error: " , e );
    	} finally {
    		if( s != null )
    			s.close( );
    	}
    }
    
    private void loadBlackListDomain( ) {
    	Scanner s = null;
    	try{
    		s = new Scanner( new File( blacklistDomainFileLocation ) );
    		blackListDomain = new ArrayList< String >( );
    		while( s.hasNext( ) ) {
    			blackListDomain.add( s.next( ) );
    		}
    	} catch( IOException e ) {
    		log.error( "Load blacklist file error: " , e );
    	} finally {
    		if( s != null )
    			s.close( );
    	}
    }
    

    private void printBlackList( ){
    	log.info( "******* BlackList Urls *******" );
    	for( String url : blacklListUrls ) 
    		log.info( "  " + url );
    	log.info("***************************");
    	
    }
     
    private void printProperties( ){
    	log.info( "********* Properties *********" );
    	log.info( "	urlBase=" +urlBase );
    	log.info( "	type=" +type );
    	log.info( "	hitsPerSite=" +hitsPerSite);
    	log.info( "	hitsPerPage=" +hitsPerPage );
    	log.info( "	NThreads=" +NThreads );
    	log.info( "	NumImgsbyUrl=" +numImgsbyUrl );
    	log.info( "	HostGetImage=" +hostGetImage );
    	log.info( "	urldirectoriesImage=" +urldirectoriesImage );
    	log.info( "	NThreads=" +NThreads );
    	log.info( "******************************" );
    }
    
}
