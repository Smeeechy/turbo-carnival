package com.company;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebCrawlerNew extends JFrame {
    private static HashMap<String, String> links;
    private static ArrayDeque<String[]> tasks;
    private static HashSet<String> checked;
    private static ArrayList<Thread> threadWorkers;
    private static String protocol;
    private static String baseURL;
    private static int maxDepth;
    private static int currentDeepestThread;
    private static int timeLimit;
    private static int totalElapsedSeconds;
    private static volatile int totalParsedPages;
    private static boolean running = false;
    private static boolean hasMaxDepth = true;
    private static boolean hasTimeLimit = true;

    private static JLabel elapsedTimeText;
    private static JLabel parsedPagesText;
    private static JTextField startURLText;
    private static JTextField workerText;
    private static JTextField maxDepthText;
    private static JTextField timeLimitText;
    private static JTextField exportText;
    private static JToggleButton runButton;
    private static JCheckBox maxDepthCheckbox;
    private static JCheckBox timeLimitCheckbox;
    private static JButton saveButton;
    private static Timer timer;

    private static final ActionListener TIMER_LISTENER = new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e) {
            totalElapsedSeconds++;
            final String formattedTime = String.format("%d:%02d:%02d", totalElapsedSeconds / 3600, (totalElapsedSeconds % 3600) / 60, totalElapsedSeconds % 60);
            elapsedTimeText.setText(formattedTime);
        }
    };

    private static final ActionListener RUN_BUTTON_LISTENER = new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent event) {
            if (!running) {
                System.out.println("Toggled run button, starting crawl...");
                runButton.setText("Stop");

                // reset everything to start from scratch
                hasTimeLimit = timeLimitCheckbox.isSelected();
                timeLimit = Integer.parseInt(timeLimitText.getText());
                totalElapsedSeconds = 0;
                elapsedTimeText.setText("0:00:00");
                hasMaxDepth = maxDepthCheckbox.isSelected();
                maxDepth = Integer.parseInt(maxDepthText.getText());
                currentDeepestThread = 0;
                totalParsedPages = 0;
                parsedPagesText.setText("0");
                timer = new Timer(1000, TIMER_LISTENER);
                timer.start();
                links = new HashMap<>();
                tasks = new ArrayDeque<>();
                checked = new HashSet<>();
                threadWorkers = new ArrayList<>();

                // save protocol and base url
                protocol = getProtocol(startURLText.getText());
                baseURL = getBaseURL(startURLText.getText());

                // add initial url to task list
                tasks.offer(new String[]{startURLText.getText(), "0"});
                running = true;

                // create and start worker threads
                for (int i = 0; i < Integer.parseInt(workerText.getText()); i++) {
                    Thread worker = new Thread(WORKER_RUNNABLE);
                    worker.setName("worker-" + i);
                    worker.setDaemon(true);
                    threadWorkers.add(worker);
                }
                for (Thread worker : threadWorkers) {
                    worker.start();
                }
                final Thread watcher = new Thread(TERMINATOR_RUNNABLE);
                watcher.setPriority(Thread.MAX_PRIORITY);
                watcher.setDaemon(true);
                watcher.start();
            } else {
                System.out.println("Untoggled run button, terminating thread workers...");
                running = false;
                runButton.setText("Stopping...");
                timer.stop();
                for (Thread worker : threadWorkers) {
                    try {
                        worker.join();
                        System.out.println(worker.getName() + " finished");
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                System.out.println("All workers have finished crawling.");
                runButton.setText("Run");
            }
        }
    };

    private static final ActionListener SAVE_BUTTON_LISTENER = new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent event) {
            Path path = Path.of(exportText.getText()).toAbsolutePath();
            try (BufferedWriter writer = Files.newBufferedWriter(path)) {
                for (Map.Entry<String, String> entry : links.entrySet()) {
                    writer.write(entry.getKey() + "\n");
                    writer.write(entry.getValue() + "\n");
                }
                System.out.println("Output saved.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    };

    private static final Runnable TERMINATOR_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            int emptyStrikes = 0;
            while (running) {
                if (hasTimeLimit && totalElapsedSeconds >= timeLimit) {
                    System.out.println("Time limit reached.");
                    runButton.doClick();
                    break;
                } else {
                    synchronized (tasks) {
                        if (tasks.isEmpty()) {
                            emptyStrikes++;
                            if (emptyStrikes == 100) {
                                System.out.println("All tasks completed.");
                                runButton.doClick();
                                break;
                            }
                        } else {
                            emptyStrikes = 0;
                        }
                    }
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    };

    private static final Runnable WORKER_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            System.out.println("Started " + Thread.currentThread().getName());
            String[] nextTask;
            while (running) {
                synchronized (tasks) {
                    if (tasks.isEmpty()) {
                        nextTask = null;
                    } else {
                        nextTask = tasks.poll();
                        System.out.println(Thread.currentThread().getName() + " removed task. Remaining: " + tasks.size());
                    }
                }
                if (nextTask == null) {
                    try {
                        Thread.currentThread().sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    continue;
                } else {
                    System.out.println("Crawling " + nextTask[0] + "...");
                    crawl(nextTask);
                    System.out.println("Finished crawling " + nextTask[0] + ".\n");
                }
            }
        }
    };

    public WebCrawlerNew() {
        // basic frame setup
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(600, 250);
        setTitle("Web Crawler");
        setLocationRelativeTo(null);

        // set up GUI containers
        JPanel contentPane = new JPanel();
        contentPane.setLayout(new BorderLayout(5, 10));
        contentPane.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        add(contentPane);

        JPanel labelPane = new JPanel();
        labelPane.setLayout(new GridLayout(7, 1, 3, 3));
        contentPane.add(labelPane, BorderLayout.WEST);

        JPanel textPane = new JPanel();
        textPane.setLayout(new GridLayout(7, 1, 3, 3));
        contentPane.add(textPane, BorderLayout.CENTER);

        JPanel buttonPane = new JPanel();
        buttonPane.setLayout(new GridLayout(7, 1, 3, 3));
        contentPane.add(buttonPane, BorderLayout.EAST);

        // create labels
        JLabel startURLLabel = new JLabel("Start URL:");
        JLabel workersLabel = new JLabel("Thread Workers:");
        JLabel maxDepthLabel = new JLabel("Maximum Depth:");
        JLabel timeLimitLabel = new JLabel("Time Limit (secs):");
        JLabel elapsedTimeLabel = new JLabel("Elapsed Time:");
        JLabel parsedPagesLabel = new JLabel("Parsed Pages:");
        JLabel exportLabel = new JLabel("Export:");
        labelPane.add(startURLLabel);
        labelPane.add(workersLabel);
        labelPane.add(maxDepthLabel);
        labelPane.add(maxDepthLabel);
        labelPane.add(timeLimitLabel);
        labelPane.add(elapsedTimeLabel);
        labelPane.add(parsedPagesLabel);
        labelPane.add(exportLabel);

        // create text fields, timer, and page counter
        startURLText = new JTextField("https://www.apple.com/");
        startURLText.setName("UrlTextField");
        workerText = new JTextField("5");
        maxDepthText = new JTextField("1");
        maxDepthText.setName("DepthTextField");
        timeLimitText = new JTextField("15");
        elapsedTimeText = new JLabel("0:00:00");
        parsedPagesText = new JLabel("0");
        parsedPagesText.setName("ParsedLabel");
        exportText = new JTextField("C:\\Users\\maccoy.smith\\Desktop\\web-crawler-v2-output.txt");
        exportText.setName("ExportUrlTextField");
        textPane.add(startURLText);
        textPane.add(workerText);
        textPane.add(maxDepthText);
        textPane.add(timeLimitText);
        textPane.add(elapsedTimeText);
        textPane.add(parsedPagesText);
        textPane.add(exportText);

        // create buttons and checkboxes
        runButton = new JToggleButton("Run");
        runButton.setName("RunButton");
        maxDepthCheckbox = new JCheckBox("Enabled", hasMaxDepth);
        maxDepthCheckbox.setName("DepthCheckBox");
        timeLimitCheckbox = new JCheckBox("Enabled", hasTimeLimit);
        saveButton = new JButton("Save");
        saveButton.setName("ExportButton");
        buttonPane.add(runButton);
        buttonPane.add(new JLabel(" "));
        buttonPane.add(maxDepthCheckbox);
        buttonPane.add(timeLimitCheckbox);
        buttonPane.add(new JLabel(" "));
        buttonPane.add(new JLabel(" "));
        buttonPane.add(saveButton);

        // add ActionListeners to buttons
        // JToggleButton "Run" - start breadth-first search using specified number of workers and within time limit
        runButton.addActionListener(RUN_BUTTON_LISTENER);

        // JButton "Save" - export crawled data to specified directory as .txt file
        saveButton.addActionListener(SAVE_BUTTON_LISTENER);

        // make frame visible
        setVisible(true);
    }

    private static void crawl(String[] url) {
        checked.add(url[0]);
        String html = getHTMLFromURL(url[0]);
        if (html != null) {
            String title = getTitleFromHTML(html);
            synchronized (links) {
                System.out.println("Added " + url[0] + " to links");
                links.put(url[0], title);
                incrementParsedPageCount();
            }
            ArrayList<String[]> anchors = getAnchorsFromHTML(html, url[1]);
            addValidAnchorsToQueue(anchors);
        }

    }

    private static String getProtocol(String url) {
        if (url.matches("^http://.*")) {
            return "http:";
        } else if (url.matches("^https://.*")) {
            return "https:";
        } else {
            return null;
        }
    }

    private static String getBaseURL(String inputURL) {
        if (inputURL.startsWith("http://")) {
            return "http://" + inputURL.substring(7).split("/")[0];
        } else if (inputURL.startsWith("https://")) {
            return "https://" + inputURL.substring(8).split("/")[0];
        } else {
            return inputURL;
        }
    }

    private static String getHTMLFromURL(String url) {
        try {
            URLConnection connection = (new URL(url)).openConnection();
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:63.0) Gecko/20100101 Firefox/63.0");
            String contentType = connection.getContentType();
            if (contentType == null) {
                return null;
            } else if (contentType.contains("text/html")) {
                BufferedInputStream inputStream = new BufferedInputStream(connection.getInputStream());
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (NullPointerException e) {
//            System.out.println("No content type found for url " + url);
        } catch (IOException e) {
//            System.out.println("Error connecting to url " + url);
        }
        return null;
    }

    private static String getTitleFromHTML(String html) {
        Pattern titlePattern = Pattern.compile("<title>.*?</title>");
        Matcher titleMatcher = titlePattern.matcher(html);
        if (titleMatcher.find()) {
            String titleText = titleMatcher.group();
            return titleText.substring(7, titleText.length() - 8);
        }
        return null;
    }

    private static ArrayList<String[]> getAnchorsFromHTML(String html, String depth) {
        if (Integer.parseInt(depth) + 1 > currentDeepestThread) {
            currentDeepestThread = Integer.parseInt(depth) + 1;
        }
        Pattern anchorPattern = Pattern.compile("<a\\b.*?>");
        Matcher anchorMatcher = anchorPattern.matcher(html);
        ArrayList<String[]> anchors = new ArrayList<>();
        while (anchorMatcher.find()) {
            anchors.add(new String[]{anchorMatcher.group(), String.valueOf(Integer.parseInt(depth) + 1)});
        }
        return anchors;
    }

    private static String getLinkFromAnchor(String anchor) {
        Pattern linkPattern = Pattern.compile("href=[\"'].*?[\"']");
        Matcher linkMatcher = linkPattern.matcher(anchor);
        if (linkMatcher.find()) {
            String linkText = linkMatcher.group();
            return linkText.substring(6, linkText.length() - 1);
        } else {
            return null;
        }
    }

    private static String getStandardizedLink(String link) {
        Pattern relativePattern = Pattern.compile("(^/[^/].*|^[^/].+)");
        Matcher relativeMatcher = relativePattern.matcher(link);
        Pattern missingProtocolPattern = Pattern.compile("^//.*");
        Matcher missingProtocolMatcher = missingProtocolPattern.matcher(link);
        if (link.startsWith("http")) {
            return link;
        } else if (relativeMatcher.matches()) {
            if (link.startsWith("/")) {
                return baseURL + link;
            } else {
                return baseURL + "/" + link;
            }
        } else if (missingProtocolMatcher.matches()) {
            return protocol + link;
        } else if (baseURL.endsWith("/")) {
            return baseURL + link;
        } else {
            return baseURL + "/" + link;
        }
    }

    private static synchronized void addValidAnchorsToQueue(ArrayList<String[]> anchors) {
        for (String[] anchor : anchors) {
            String link = getLinkFromAnchor(anchor[0]);
            if (link != null && link.length() > 1 && !link.startsWith("#") && !link.startsWith("mailto:")) {
                link = getStandardizedLink(link);
                if (!checked.contains(link) && Integer.parseInt(anchor[1]) <= maxDepth) {
                    tasks.add(new String[]{link, anchor[1]});
                    System.out.println(Thread.currentThread().getName() + " added " + link + ". Remaining: " + tasks.size());
                }
            }
        }
    }

    private static void incrementParsedPageCount() {
        totalParsedPages++;
        parsedPagesText.setText(String.valueOf(totalParsedPages));
    }
}
