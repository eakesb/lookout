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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
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
    private final String sender = "bceakes@gmail.com";
    private final String[] recipients = new String[]{sender};

    public Main(String[] args) {
        sesClient = AmazonSimpleEmailServiceClientBuilder.standard()
                .withRegion(Regions.US_WEST_2)
                .build();

        executorService = Executors.newSingleThreadScheduledExecutor();
        sites = ImmutableList.of(
                "75097/146537",
                "131440/403270"
        );
    }

    private void start() {
        executorService.scheduleAtFixedRate(this::run, 0, 30, TimeUnit.SECONDS);
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
            Optional<String> available = checkAvailability(ids.get(0), ids.get(1));
            available.ifPresent(this::sendEmail);
        }
    }

    private void sendEmail(String message) {
        try {
            log.info(message);
            log.info("Sending Email to : " + recipients);

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
            log.info(result.getMessageId());

        } catch (Exception e) {
            log.error("Failed to send email", e);
        }
    }

    private Optional<String> checkAvailability(String parkId, String siteId) {

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
            return Optional.empty();
        }

        String month = doc.getElementsByClass("weeknav month").get(0).getElementsByTag("span").text();
        List<String> split = Splitter.on("-").splitToList(month);
        if (split.size() > 1) {
            month = split.get(1);
        }

        String day = doc.getElementById("day01date").text();

        DateTime dateTime = DateTime.parse(month + " " + day, DateTimeFormat.forPattern("MMM YYYY dd"));

        if (dateTime.getMonthOfYear() >= 6) {
            return Optional.empty();
        }

        return Optional.of(dateTime.toString());
    }

    public static void main(String[] args) {

        Main main = new Main(args);
        Runtime.getRuntime().addShutdownHook(new Thread(main::shutdown));
        main.start();
    }
}
