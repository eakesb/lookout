import com.amazonaws.regions.Regions;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simpleemail.model.Body;
import com.amazonaws.services.simpleemail.model.Content;
import com.amazonaws.services.simpleemail.model.Destination;
import com.amazonaws.services.simpleemail.model.Message;
import com.amazonaws.services.simpleemail.model.SendEmailRequest;
import com.amazonaws.services.simpleemail.model.SendEmailResult;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import org.apache.commons.configuration.CompositeConfiguration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.configuration.SystemConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    private static Logger log = LogManager.getLogger(Main.class);

    private static final String url = "https://www.recreation.gov/campsiteDetails.do?findavail=next&arvdate=%2d/%2d/%4d&contractCode=NRSO&parkId=%s&siteId=%s";

    private final AmazonSimpleEmailService sesClient;
    private final ScheduledExecutorService executorService;

    private final ImmutableList<String> sites;
    private final String sender;
    private final String[] recipients;
    private final DateTime cutoff;
    private final long periodMinutes;

    public Main(CompositeConfiguration config) {
        sesClient = AmazonSimpleEmailServiceClientBuilder.standard()
                .withRegion(Regions.US_WEST_2)
                .build();

        executorService = Executors.newSingleThreadScheduledExecutor();

        sender = config.getString("sender", "bceakes@gmail.com");
        recipients = config.getStringArray("recipients");
        sites = ImmutableList.copyOf(config.getStringArray("lookouts"));
        cutoff = DateTime.parse(config.getString("cutoff", "2018-06-01"), DateTimeFormat.forPattern("YYYY-MM-dd"));
        periodMinutes = config.getLong("periodMinutes", 5);
    }

    private void start() {
        executorService.scheduleAtFixedRate(this::run, 0, periodMinutes, TimeUnit.MINUTES);
    }

    private void shutdown() {
        executorService.shutdown();
        try {
            executorService.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.warn("Interrupted waiting for executor to shutdown");
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        for (String site : sites) {
            List<String> ids = Splitter.on("/").splitToList(site);
            Optional<String> available = checkAvailability(ids.get(0), ids.get(1), ids.get(2));
            available.ifPresent(this::sendEmail);
        }
    }

    private void sendEmail(String message) {
        try {

            SendEmailRequest request = new SendEmailRequest()
                    .withDestination(new Destination().withBccAddresses(recipients))
                    .withMessage(new Message()
                            .withSubject(new Content()
                                    .withCharset("UTF-8")
                                    .withData("LOOKOUT AVAILABLE"))
                            .withBody(new Body()
                                    .withText(new Content()
                                            .withCharset("UTF-8")
                                            .withData(message))))
                    .withSource(sender);

            SendEmailResult result = sesClient.sendEmail(request);

            log.info("Sent Email: " + result.getMessageId());
        } catch (Exception e) {
            log.error("Failed to send email", e);
        }
    }

    private Optional<String> checkAvailability(String lookout, String parkId, String siteId) {

        log.info("Checking " + lookout);

        DateTime now = DateTime.now();

        Document doc;
        try {
            doc = Jsoup.connect(String.format(url, now.getMonthOfYear(), now.getDayOfMonth(), now.getYear(), parkId, siteId)).get();
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }

        Optional<String> notAvailable = doc.getElementById("errorMessages").getElementsByClass("msg error")
                .stream()
                .map(Element::text)
                .filter(msg -> msg.contains("Campsite is not available"))
                .findAny();

        if (notAvailable.isPresent()) {
            log.info(lookout + " not available");
            return Optional.empty();
        }

        String month = doc.getElementsByClass("weeknav month").get(0).getElementsByTag("span").text();
        List<String> split = Splitter.on("-").splitToList(month);
        if (split.size() > 1) {
            month = split.get(1);
        }

        String day = doc.getElementById("day01date").text();

        DateTime dateTime = DateTime.parse(month + " " + day, DateTimeFormat.forPattern("MMM YYYY dd"));

        if (!dateTime.isBefore(cutoff)) {
            log.info(lookout + " not available before cutoff");
            return Optional.empty();
        }

        log.info(lookout + " IS AVAILABLE: " + dateTime.toString());
        return Optional.of(dateTime.toString());
    }

    public static void main(String[] args) {

        Main main = new Main(getConfiguration(args));
        Runtime.getRuntime().addShutdownHook(new Thread(main::shutdown));
        main.start();
    }

    private static CompositeConfiguration getConfiguration(String[] args) {
        CompositeConfiguration compositeConfiguration = new CompositeConfiguration();
        compositeConfiguration.addConfiguration(new SystemConfiguration());

        if (args.length == 1) {
            String configFilePath = args[0];
            boolean exists = Files.exists(Paths.get(configFilePath));
            if (!exists) {
                System.err.println("Configuration file not found at '" + configFilePath + "");
                System.exit(1);
            }

            try {
                compositeConfiguration.addConfiguration(new PropertiesConfiguration(configFilePath));
            } catch (ConfigurationException e) {
                log.error("Failed to read configuration file at path: " + configFilePath, e);
                System.exit(1);
            }
        }

        return compositeConfiguration;
    }
}
