package org.open4goods.crawler.services.fetching;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.open4goods.config.yml.datasource.CsvDataSourceProperties;
import org.open4goods.config.yml.datasource.DataSourceProperties;
import org.open4goods.config.yml.datasource.HtmlDataSourceProperties;
import org.open4goods.crawler.repository.CsvIndexationRepository;
import org.open4goods.crawler.services.DataFragmentCompletionService;
import org.open4goods.crawler.services.IndexationService;
import org.open4goods.exceptions.ValidationException;
import org.open4goods.helper.InStockParser;
import org.open4goods.helper.ProductStateParser;
import org.open4goods.helper.ShippingCostParser;
import org.open4goods.helper.ShippingTimeParser;
import org.open4goods.model.constants.InStock;
import org.open4goods.model.constants.ProductState;
import org.open4goods.model.constants.ReferentielKey;
import org.open4goods.model.constants.ResourceTagDictionary;
import org.open4goods.model.crawlers.FetchCsvStats;
import org.open4goods.model.data.DataFragment;
import org.open4goods.model.data.Price;
import org.open4goods.model.data.Rating;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.google.common.collect.Sets;

import edu.uci.ics.crawler4j.crawler.CrawlController;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
/**
 * Worker thread that asynchronously dequeue the DataFragments from the file queue. It
 * @author goulven
 *
 */
public class CsvIndexationWorker implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(CsvIndexationWorker.class);


	private final static ObjectMapper csvMapper = new CsvMapper().enable((CsvParser.Feature.IGNORE_TRAILING_UNMAPPABLE));
	
	private static final String CLASSPATH_PREFIX = "classpath:";
	
	/** The service used to "atomically" fetch and store / update DataFragments **/
	private final CsvDatasourceFetchingService  csvService;
	
	private WebDatasourceFetchingService webFetchingService;
	
	private final IndexationService indexationService;

	private final DataFragmentCompletionService completionService;
	
	private final CsvIndexationRepository csvIndexationRepository;

	
	
	/** The duration of the worker thread pause when nothing to get from the queue **/
	private final int pauseDuration;

	/**
	 * Constructor
	 * @param csvService
	 * @param dequeuePageSize
	 */
	public CsvIndexationWorker(final CsvDatasourceFetchingService csvService, DataFragmentCompletionService completionService, IndexationService indexationService, WebDatasourceFetchingService webFetchingService, CsvIndexationRepository csvIndexationRepository,  final int pauseDuration) {
		this.csvService = csvService;
		this.pauseDuration = pauseDuration;
		this.completionService = completionService;
		this.indexationService = indexationService;
		this.webFetchingService = webFetchingService;
		this.csvIndexationRepository = csvIndexationRepository;

	}

	@Override
	public void run() {

		// TODO : exit thread condition
		while (true) {	
			try {
				if (!csvService.getQueue().isEmpty()) {
					// There is data to consume and queue consummation is enabled
					
					DataSourceProperties ds = csvService.getQueue().take();
					
					logger.info("will index {}",ds.getDatasourceConfigName());
					fetch(ds);
					logger.info("indexed {}",ds.getDatasourceConfigName());					
					
//					logger.info("{} has indexed {} DataFragments. {} Remaining in queue",workerName,  buffer.size(), service.getQueue().size());

				} else {
					try {
						logger.debug("No DataFragments to dequeue. Will sleep {}ms",pauseDuration);
						Thread.sleep(pauseDuration);
					} catch (final InterruptedException e) {
					}
				}
			} catch (final Exception e) {
				logger.error("Error while dequeing DataFragments",e);
			}
		}
	}
	
	
	public void fetch(DataSourceProperties dsProperties) {
		
		// Creating a direct web crawler if the csv fetching is followed by webFetching

		final HtmlDataSourceProperties crawlConfig = dsProperties.getCsvDatasource().getWebDatasource();
		DataFragmentWebCrawler crawler = null;
		CrawlController controler = null;
		
		String dsConfName = dsProperties.getDatasourceConfigName();
		if (null != crawlConfig) {
			try {
				logger.info("Configuring direct crawler for CSV datasource {}", dsProperties.getDatasourceConfigName());
				controler = webFetchingService.createCrawlController("csv-" + dsConfName,
						dsProperties.getCsvDatasource().getWebDatasource().getCrawlConfig());
				crawler = webFetchingService.createWebCrawler(dsConfName, dsProperties,
						dsProperties.getCsvDatasource().getWebDatasource());

				crawler.setShouldFollowLinks(false);

			} catch (final Exception e) {
				logger.error("Error while starting the CSV associated web crawler", e);
			}
		}

		final CsvDataSourceProperties config = dsProperties.getCsvDatasource();
		logger.info("Fetching CSV datasource {} ", dsConfName);

		
		/////////////////////////////
		// Csv Shema definition
		////////////////////////////
		
		CsvSchema schema = CsvSchema.emptySchema()
					.withHeader()
					.withColumnSeparator(config.getCsvSeparator())						
					;

		 if (null != config.getCsvQuoteChar()) {
			 schema = schema.withQuoteChar(config.getCsvQuoteChar().charValue());
		 } else {
			 schema = schema.withoutQuoteChar();
		 }
		 
		 if (null != config.getCsvEscapeChar()) {
			 schema = schema.withEscapeChar(config.getCsvEscapeChar());
		 }
		
		
		Set<String> urls = config.getDatasourceUrls() ;	
		
		for (final String url : urls) {
			
			FetchCsvStats stats = new FetchCsvStats(url, dsConfName);
			
			int okItems = 0;
			int validationFailedItems = 0;
			int errorItems = 0;
			int excludedItems = 0;
			
			try {

				// configure the reader on what bean to read and how we want to write
				// that bean
				final ObjectReader oReader = csvMapper.readerFor(Map.class).with(schema);

				// local file download, then estimate number of rows
//					TODO(design,P2,0.5) : Allow CSV file forwarding on remote crawl (for now, CSV with classpath fetching only works on local node)
				File destFile = File.createTempFile("csv", dsConfName+".csv");
				logger.info("Downloading CSV for {} from {} to {}", dsConfName, url, destFile);

				if (url.startsWith("http")) {
					// These are http resources
					FileUtils.copyURLToFile(new URL(url), destFile);

				} else if (url.startsWith(CLASSPATH_PREFIX)) {
					final ClassPathResource res = new ClassPathResource(url.substring(CLASSPATH_PREFIX.length()));

					// These are classpath resources
					FileUtils.copyInputStreamToFile(res.getInputStream(), destFile);
				} else {
					// These are files
					destFile = new File(url);
				}

				
				if (config.getGzip()) {
					// Awin are gziped
					
					File tmpFile = File.createTempFile("gzip", "awin");

					decompressGzipFile(destFile.getAbsolutePath(), tmpFile.getAbsolutePath());

					// Switching and cleaning
					String toDelete = destFile.getAbsolutePath();
					destFile = new File(tmpFile.getAbsolutePath());
					new File(toDelete).delete();
					
				} else	if (config.getZiped()) {

					// Unzipping the files
					try {
						// Unzipping the data

						final ZipFile zipFile = new ZipFile(destFile);
//							File zipedDestFile = File.createTempFile("csv_zipped", dsProperties.getName());

						final String targetFolder = destFile.getParent() + File.separator + "unziped";

						logger.info("Unzipping CSV data from {} to {}", destFile.getAbsolutePath(),
								targetFolder);

						new File(targetFolder).mkdirs();
						zipFile.extractAll(targetFolder);
						zipFile.close();

						FileUtils.deleteQuietly(destFile);
						final File zipedDestFolder = new File(targetFolder);

						if (zipedDestFolder.list().length > 1) {
							logger.error("Multiple files in {}, cannot operate",
									destFile.getAbsolutePath());
							csvService.getRunning().remove(dsConfName);
							FileUtils.deleteQuietly(zipedDestFolder);
							return;
						}

						for (final File f : zipedDestFolder.listFiles()) {
							destFile = f;
						}

					} catch (final ZipException e) {
						logger.error("Error extracting CSV data", e);
					}
				}
				
				
				// Row number counting

				logger.info("Counting lines for {} ", destFile.getAbsolutePath());

				// NOTE : Choice is made not to have the queue, to avoid this long line counting
//					final Path path = Paths.get(destFile.getAbsolutePath());
//					final long linesCount = Files.lines(path).count();
				final long linesCount = 0L;
				csvService.getRunning().get(dsConfName).setQueueLength(linesCount);

				logger.info("Starting {} CSV lines fetching of {} ", linesCount, destFile.getAbsolutePath());
				
				csvService.getRunning().get(dsConfName).getFilesCounters().put(url, 0L);
				
				final MappingIterator<Map<String, String>> mi = oReader.readValues(destFile);
				
				while (mi.hasNext() && !csvService.getRunning().get(dsConfName).isShuttingDown()) {
					Map<String, String> line = null;
					try {

						stats.incrementLines();
						
						// stats update
						csvService.getRunning().get(dsConfName).incrementProcessed(url);
						csvService.getRunning().get(dsConfName).decrementQueue();

						// Handle the csv line
						line = mi.next();
						if (null == line) {
							logger.warn("Null line");
							stats.incrementErrors();
							continue;
						}							

						final DataFragment df = parseCsvLine(crawler, controler, dsProperties, line, dsConfName, logger);

						// Effectiv indexation
						if (null != df) {
							indexationService.index(df, dsConfName);
							stats.incrementIndexed();
							okItems++;
						} else {
							throw new ValidationException("Null data fragment");
						}

					} catch (final ValidationException e) {
						stats.incrementValidationFail();
						validationFailedItems++;
						logger.info("Validation exception while parsing {} : {}", line, e.getMessage());
					} catch (final Exception e) {
						stats.incrementErrors();
						errorItems++;
						logger.warn("error in {}, while parsing {} :\n {}.", dsProperties.getDatasourceConfigName(), e.getMessage());
					}
				}
				
				// closing iterator
				mi.close();
				
				logger.info("End csv fetching for {}:{}. {} imported, {} validations failed, {} excluded, {} errors ", dsConfName, url, okItems, validationFailedItems, excludedItems, errorItems);

				
				
				logger.info("Removing fetched CSV file at {}", destFile);
				if (url.startsWith("http")) {
					FileUtils.deleteQuietly(destFile);
				}

			} catch (final Exception e) {
				logger.error("CSV fetching aborted : {}:{} ",dsConfName ,url,e);
				logger.info("End csv fetching for {}{}. {} imported, {} validations failed, {} excluded, {} errors ", dsConfName, url,  okItems, validationFailedItems, excludedItems, errorItems);
			} 

			// Saving stats 
			stats.terminate();					
			csvIndexationRepository.save(stats);
		}
		// Calling the finished to collect stats
		csvService.finished(csvService.stats().get(dsConfName), dsProperties);

		

		
		
		if (null != crawler) {
			logger.info("Terminating the CSV direct crawl controller for {}", dsConfName);
			controler.shutdown();

		}
		csvService.getRunning().remove(dsConfName);

		logger.info("End csv direct fetching for {}", dsConfName);

	}

	

	

	private DataFragment parseCsvLine(final DataFragmentWebCrawler crawler, final CrawlController controler,
			final DataSourceProperties config, final Map<String, String> item, final String datasourceConfigName,
			final Logger dedicatedLogger) throws ValidationException {

		final CsvDataSourceProperties csvProperties = config.getCsvDatasource();

		dedicatedLogger.info("Parsing line : {}", item);
		
		
		
		/////////////////////////////////////
		// Applying filtering rules
		//////////////////////////////////////
		if (csvProperties.getColumnsFilter().size() > 0) {
			boolean handle = false;
			for (final Entry<String, Set<String>> entry : csvProperties.getColumnsFilter().entrySet()) {
				final String val = item.get(entry.getKey());
				if (entry.getValue().contains(val)) {
					handle = true;
					break;
				}
			}
			if (!handle) {
				throw new ValidationException("Item " + item + " will be filtered according to filtering rules");
			}
		}

		/////////////////////////////////////
		// Applying replacements rules
		//////////////////////////////////////
		
		
		
		
		/////////////////////////////////
		// DataFragments mapping
		//////////////////////////////////

		final DataFragment p = new DataFragment();

		// Url extraction from param
		try {
			if (!StringUtils.isEmpty(csvProperties.getExtractUrlFromParam())) {
				String u = getFromCsvRow(item, csvProperties.getUrl());
				final UriComponents parsedUrl = UriComponentsBuilder.fromUriString(u).build();
				u = URLDecoder.decode(parsedUrl.getQueryParams().getFirst(csvProperties.getExtractUrlFromParam()), StandardCharsets.UTF_8);
				p.setUrl(u);
			} else {
				p.setUrl(getFromCsvRow(item, csvProperties.getUrl()));

			}
		} catch (final Exception e2) {
			dedicatedLogger.warn("Error while extracting url of {}", item);
		}

		if (!StringUtils.isEmpty(csvProperties.getAffiliatedUrl())) {
			String u = getFromCsvRow(item, csvProperties.getAffiliatedUrl());
			if (null != csvProperties.getAffiliatedUrlReplacementTokens()) {
				for (Entry<String, String> a : csvProperties.getAffiliatedUrlReplacementTokens().entrySet()) {
					u = u.replace(a.getKey(), a.getValue());
				}
			}
			p.setAffiliatedUrl(u);
		}

		if (csvProperties.getTrimUrlParameters()) {

			final int pos = p.getUrl().indexOf('?');
			if (-1 != pos) {
				p.setUrl(p.getUrl().substring(0, pos));
			}
		}

		for(String pc : csvProperties.getPrice()) {
			try {

				final Price price = new Price();
				String val = getFromCsvRow(item, pc);
				
				if (StringUtils.isEmpty(val)) {
					continue;
				}
				
				price.setPriceValue(val, Locale.forLanguageTag(config.getLanguage().toUpperCase()));
				price.setCurrency(csvProperties.getCurrency());

				p.setPrice(price);
				break;
			} catch (final Exception e) {
				dedicatedLogger.info("Error setting price, trying setPriceAndCurrency : {}", p.getUrl());
				try {
					p.setPriceAndCurrency(getFromCsvRow(item, pc),
							Locale.forLanguageTag(config.getLanguage().toUpperCase()));
				} catch (final Exception e1) {
					dedicatedLogger.warn("Error setting fallback price with setPriceAndCurrency(): {}", p.getUrl());
				}
			}
		}

		p.addName(getFromCsvRow(item, csvProperties.getName()));
		p.addProductTags(getCategoryFromCsvRows(item, csvProperties.getProductTags()));

		if (!StringUtils.isEmpty(csvProperties.getAttrs())) {
			handleAttributes(p, config, getFromCsvRow(item, csvProperties.getAttrs()),dedicatedLogger);
		}
		
		
		/////////////////////////////////////
		// Adding all columns as attributes
		/////////////////////////////////////
		if (csvProperties.getImportAllAttributes()) {			
			for (Entry<String, String> kv : item.entrySet()) {
				String key = kv.getKey();
				String val = kv.getValue();
							
				if (!StringUtils.isEmpty(val)) {				
						p.addAttribute(key,val,config.getLanguage(), csvProperties.getAttributesIgnoreCariageReturns(),csvProperties.getAttributesSplitSeparators());
				}
				
			}
		}

		// Attributes
		for (final Entry<String, String> desc : csvProperties.getAttributes().entrySet()) {
			p.addAttribute(desc.getValue(), getFromCsvRow(item, desc.getKey()),
							config.getLanguage(), csvProperties.getAttributesIgnoreCariageReturns(),
							csvProperties.getAttributesSplitSeparators());
		}

		// Rating
		
		
		

		if (null != csvProperties.getRating()) {
			try {
				final Rating r = new Rating();
//				r.setProviderName(config.getName());
//				r.setTimeStamp(System.currentTimeMillis());
				r.setMin(csvProperties.getRating().getMinValue());
				r.setMax(csvProperties.getRating().getMaxValue());
				r.addTag(csvProperties.getRating().getType());
				r.setValue(Double.valueOf(getFromCsvRow(item, csvProperties.getRating().getValue())));
//				r.setUrl(p.getUrl());
				p.addRating(r);
			} catch (final Exception e) {
				dedicatedLogger.warn("Error while adding rating for {} : {}", item, e.getMessage());
			}
		}

		for (final String desc : csvProperties.getDescription()) {
			
			String description = getFromCsvRow(item, desc);
			
			
			if (!StringUtils.isEmpty(description) && null != config.getDescriptionRemoveToken()) {
				
				for (String token : config.getDescriptionRemoveToken()) {
					description = description.replace(token, "");
				}				
			}
			
			if (!StringUtils.isEmpty(description)) {				
				p.addDescription(description, config.getLanguage());
			}			
		}

		try {
			for (final String imgCell : csvProperties.getImage()) {
				String r = getFromCsvRow(item, imgCell);
				if (!StringUtils.isEmpty(r)) {
					
				    // Checking for image tokens exclusions
					if (null != csvProperties.getImageTokenExclusions()) {
						boolean skip = false;
						for (String re : csvProperties.getImageTokenExclusions()) {
							if (r.contains(re)) {
								skip = true;
								break;
							}
						}
						if (skip) {
							continue;
						}						
					}
					
					p.addResource(r, ResourceTagDictionary.CSV);
				
				
				}
			}
		} catch (final ValidationException e1) {
			dedicatedLogger.warn("Problem while adding resource for {}", item);
		}

		
		// Assuming that by default, for referentiels they are in stock
		p.setInStock(InStock.INSTOCK);
		for (String inStock : csvProperties.getInStock()) {
			// Instock
				try {
					String val = getFromCsvRow(item, inStock);
					if (!StringUtils.isEmpty(val)) {
						InStock stock = InStockParser.parse(val);
						if (null != stock) {
							p.setInStock(stock);
							break;
						}
					}
				} catch (final Exception e1) {
					dedicatedLogger.warn("Cannot parse InStock : {} ", e1.getMessage());
				}			
		}

		// Shipping time
		if (!StringUtils.isEmpty(csvProperties.getShippingTime())) {
			final String strW = getFromCsvRow(item, csvProperties.getShippingTime());
			if (StringUtils.isEmpty(strW)) {
				dedicatedLogger.warn("No  ShippingCost in csv column {}", csvProperties.getShippingTime());
			} else {
				try {
					p.setShippingTime(ShippingTimeParser.parse(strW));
				} catch (final Exception e1) {
					dedicatedLogger.warn("Cannot parse shippingTime : {} ", e1.getMessage());
				}
			}
		}

		// Instock quantity
		if (!StringUtils.isEmpty(csvProperties.getQuantityInStock())) {
			final String strW = getFromCsvRow(item, csvProperties.getQuantityInStock());
			if (StringUtils.isEmpty(strW)) {
				dedicatedLogger.warn("No  ShippingCost in csv column {}", csvProperties.getQuantityInStock());
			} else {
				try {
					p.setQuantityInStock(Integer.valueOf(strW));
				} catch (final Exception e1) {
					dedicatedLogger.warn("Cannot parse QuantityInStock : {} ", e1.getMessage());
				}
			}
		}

		// Shipping price
		if (!StringUtils.isEmpty(csvProperties.getShippingCost())) {
			final String strW = getFromCsvRow(item, csvProperties.getShippingCost());
			if (StringUtils.isEmpty(strW)) {
				dedicatedLogger.warn("No  ShippingCost in csv column {}", csvProperties.getShippingCost());
			} else {
				try {
					p.setShippingCost(ShippingCostParser.parse(strW));
				} catch (final Exception e1) {
					dedicatedLogger.warn("Cannot parse ShippingCost : {} ", e1.getMessage());
				}
			}
		}

		// Warranty
		if (!StringUtils.isEmpty(csvProperties.getWarranty())) {
			try {
				final String strW = getFromCsvRow(item, csvProperties.getWarranty());
				if (StringUtils.isEmpty(strW)) {
					dedicatedLogger.warn("No  warranty in csv column {}", csvProperties.getWarranty());
				} else {
					p.setWarranty(Integer.valueOf(strW));
				}
			} catch (final Exception e1) {
				dedicatedLogger.warn("Cannot parse Warranty : {} ", e1.getMessage());
			}
		}

		// ProductState
		p.setProductState(config.getDefaultItemCondition());
		for (String productState : csvProperties.getProductState()) {

				try {
					String raw = getFromCsvRow(item, productState);
					if (StringUtils.isEmpty(raw)) {
                        continue;
                    }
					ProductState state = ProductStateParser.parse(raw);
					
					if (null != state) {
						p.setProductState(state);
						break;
					}
				} catch (final Exception e1) {				
						dedicatedLogger.warn("Cannot parse product state : {} ", e1.getMessage());
				}

		}
		

		for (final Entry<ReferentielKey, Set<String>> refs : csvProperties.getReferentiel().entrySet()) {
			for (String csvKey : refs.getValue()) {
				
				final String val = getFromCsvRow(item, csvKey);
				if (StringUtils.isEmpty(val)) {
					dedicatedLogger.debug("No data for {} in {}", csvKey, item);
				} else {
					p.addReferentielAttribute(refs.getKey(), val);
					break;
				}
			}
			
			if (null == p.getReferentielAttributes().get(refs.getKey())) {
				dedicatedLogger.info("No referentiel attribute found for {} in {}", refs.getKey(), item);
			}
			
			
		}

		// If an affiliated url and no url, use affiliatedUrl

		if (StringUtils.isEmpty(p.getUrl()) && !StringUtils.isEmpty(p.getAffiliatedUrl())) {
			p.setUrl(p.getAffiliatedUrl());
		}

		// We import all columns as attributes
		if (csvProperties.getImportAllAttributes()) {

			for (Entry<String, String> entry : item.entrySet()) {
				p.addAttribute(sanitize(entry.getKey()), sanitize(entry.getValue()), config.getLanguage(), true,
						Sets.newHashSet());
			}

		}

		// Completing with the web data if defined
		// TODO : && !StringUtils.isEmpty(p.gtin()
		if (null != crawler) {
			dedicatedLogger.info("Completing CSV data fragment {} with web data at url {}", p, p.getUrl());

			if (!StringUtils.isEmpty(p.getUrl())) {
				// Completing the datafragment with the configured url
				DataFragment fragment = crawler.visitNow(controler, p.getUrl(), p);
				
				
				
				p.setDatasourceConfigName(datasourceConfigName);

			} else {
				dedicatedLogger.warn(
						"No url to crawl extracted from datafragment {}, will index without web crawling completion", p);
			}
		} else {
			// NOTE : completion service is made by completionCrawler
			////// "Standard" completion of the data fragment
			completionService.complete(p, datasourceConfigName, config, dedicatedLogger);
		}

		p.validate(config.getValidationFields());

		return p;

	}

//
//	/**
//	 * Sanitisation using libreoffice
//	 * @param destFile
//	 * @param config
//	 * @param dedicatedLogger 
//	 * @return
//	 */
//	private File libreOfficeSanitisation(File destFile, CsvDataSourceProperties config, Logger dedicatedLogger) {
//
//		// libreoffice --headless --convert-to csv:"Text - txt - csv (StarCalc)":59,34,76,,,,true /home/goulven/Bureau/products_405199502.csv --outdir /tmp/libreofficeCSV --infilter=CSV:59,34,UTF8
//		
//		String outDir = System.getProperty("java.io.tmpdir")+ File.separator+"libreofficeCSV";
//		String fileName = destFile.getName();
//		
////		int fieldSeparator=59;
////		int textSeparator=34;
//
//		int fieldSeparator=config.getCsvSeparator();
//		int textSeparator=config.getCsvQuoteChar() == null ? 0 : config.getCsvQuoteChar();
//		
//		//NOTE :  76 represents utf8 encoding
//		ProcessBuilder builder = new ProcessBuilder("libreoffice", "--headless", "--convert-to", "csv:Text - txt - csv (StarCalc):"+(int)SANITISED_COLUMN_SEPARATOR+","+(int)SANITIZED_QUOTE_CHAR+",76,,,,true", destFile.getAbsolutePath(), "--outdir", outDir, "--infilter=CSV:"+fieldSeparator+","+textSeparator+","+config.getCsvEncoding());
//
//		try {
//			Process process = builder.start();
//			process.waitFor();
//
//			logger.info("Libreoffice conversion result : {}", IOUtils.toString(process.getInputStream(),Charset.defaultCharset()));
//			
//			String error = IOUtils.toString(process.getErrorStream(),Charset.defaultCharset());
//			
//			IOUtils.closeQuietly(process.getErrorStream());
//			IOUtils.closeQuietly(process.getInputStream());
//			 
//			 
//			if (!StringUtils.isEmpty(error)) {
//				dedicatedLogger.error("Error returned by libreoffice converter. Sanitisation will be skipped : {}",error);
//				logger.error("Error returned by libreoffice converter. Sanitisation will be skipped : {}",error);
//			}
//			else {				
//				destFile.delete();
//				return new File(outDir+File.separator+fileName);
//			}
//		
//		} catch (IOException | InterruptedException e) {
//			logger.error("Error with libreoffice transformation",e);
//		}
//		return destFile;
//
//	
//	
//	}

	




	
	
	
    private  void decompressGzipFile(String gzipFile, String newFile) {
        try {
            FileInputStream fis = new FileInputStream(gzipFile);
            GZIPInputStream gis = new GZIPInputStream(fis);
            FileOutputStream fos = new FileOutputStream(newFile);
            byte[] buffer = new byte[1024];
            int len;
            while((len = gis.read(buffer)) != -1){
                fos.write(buffer, 0, len);
            }
            //close resources
            fos.close();
            gis.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        
    }


	private void handleAttributes(final DataFragment pd, final DataSourceProperties config, final String attrRaw, Logger dedicatedLogger) {

		if (StringUtils.isEmpty(attrRaw)) {
			return;
		}

		final String[] lines = attrRaw.split(config.getCsvDatasource().getAttributesSplitChar());

		for (final String line : lines) {

			final String[] frags = line.split(config.getCsvDatasource().getAttributesKeyValSplitChar());
			if (frags.length != 2) {
				dedicatedLogger.info("Was expecting two fragments, got {} : {} at {}", frags.length, line, config.getName());
			} else {
				String key = sanitize(frags[0]);
				if (null != config.getCsvDatasource().getAttributesKeyKeepAfter()) {

					int pos = key.indexOf(config.getCsvDatasource().getAttributesKeyKeepAfter());
					if (-1 != pos) {
						key = key.substring(pos + 1).trim();
					}
				}

				// Splitters from conf
				pd.addAttribute(key, sanitize(frags[1]), config.getLanguage(), true, Sets.newHashSet());
			}
		}

	}

	/**
	 * Get a value from a named csv row
	 *
	 * @param item
	 * @param colName
	 * @return
	 */
	private String getFromCsvRow(final Map<String, String> item, final String colName) {

		final String val = item.get(colName);
		if (null != val) {
			return sanitize(val);
		} else {
			return null;
		}
	}

	private List<String> getCategoryFromCsvRows(Map<String, String> item, List<String> colNames) {
		List<String> ret = new ArrayList<>();

		for (String colName : colNames) {
			String val = getFromCsvRow(item, colName);
			if (!StringUtils.isEmpty(val)) {
				ret.add(val);
			}
		}
		return ret;
	}

	// TODO(design,0.25,P3) : Externalize / mutualize at addAttribute level
	public static String sanitize(final String input) {
		return StringUtils.normalizeSpace(StringEscapeUtils.unescapeHtml4(input));
	}

    
}