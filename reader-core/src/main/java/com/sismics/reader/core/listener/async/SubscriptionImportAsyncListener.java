package com.sismics.reader.core.listener.async;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.eventbus.Subscribe;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import com.sismics.reader.core.constant.Constants;
import com.sismics.reader.core.dao.file.json.StarredReader;
import com.sismics.reader.core.dao.file.opml.OpmlFlattener;
import com.sismics.reader.core.dao.file.opml.OpmlReader;
import com.sismics.reader.core.dao.file.opml.Outline;
import com.sismics.reader.core.dao.file.rss.GuidFixer;
import com.sismics.reader.core.dao.jpa.*;
import com.sismics.reader.core.dao.jpa.criteria.ArticleCriteria;
import com.sismics.reader.core.dao.jpa.criteria.FeedSubscriptionCriteria;
import com.sismics.reader.core.dao.jpa.criteria.UserArticleCriteria;
import com.sismics.reader.core.dao.jpa.dto.ArticleDto;
import com.sismics.reader.core.dao.jpa.dto.FeedSubscriptionDto;
import com.sismics.reader.core.dao.jpa.dto.UserArticleDto;
import com.sismics.reader.core.event.ArticleCreatedAsyncEvent;
import com.sismics.reader.core.event.SubscriptionImportedEvent;
import com.sismics.reader.core.model.context.AppContext;
import com.sismics.reader.core.model.jpa.*;
import com.sismics.reader.core.service.FeedService;
import com.sismics.reader.core.util.EntityManagerUtil;
import com.sismics.reader.core.util.TransactionUtil;
import com.sismics.util.mime.MimeType;
import com.sismics.util.mime.MimeTypeUtil;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.MalformedURLException;
import java.text.MessageFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Listener on subscriptions import request.
 * 
 * @author jtremeaux
 */
public class SubscriptionImportAsyncListener {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(SubscriptionImportAsyncListener.class);

    /**
     * Starred articles file name (Google Takeout).
     */
    private static final String FILE_STARRED_JSON = "starred.json";

    /**
     * Subscription file name (Google Takeout).
     */
    private static final String FILE_SUBSCRIPTIONS_XML = "subscriptions.xml";

    /**
     * Process the event.
     *
     * @param subscriptionImportedEvent OPML imported event
     */
    @Subscribe
    public void onSubscriptionImport(final SubscriptionImportedEvent subscriptionImportedEvent) throws Exception {
        if (log.isInfoEnabled()) {
            log.info(MessageFormat.format("OPML import requested event: {0}", subscriptionImportedEvent.toString()));
        }

        final User user = subscriptionImportedEvent.getUser();
        final File importFile = subscriptionImportedEvent.getImportFile();

        TransactionUtil.handle(() -> {
            Job job = createJob(user, importFile);
            if (job != null) {
                processImportFile(user, importFile, job);
            }
        });
    }

    /**
     * Read the file to import in a 1st pass to know the number of feeds / starred articles to import
     * and create a new job.
     *
     * @param user       User
     * @param importFile File to import
     * @return The new job
     */


    private Job createJob(final User user, File importFile) {
        long outlineCount = 0;
        final AtomicInteger starredCount = new AtomicInteger();
        Closer closer = Closer.create();
        try {
            String mimeType = MimeTypeUtil.guessMimeType(importFile);
            if (MimeType.APPLICATION_ZIP.equals(mimeType)) {
                outlineCount = processZipFileForJob(importFile, closer, starredCount);
            } else {
                outlineCount = processOpmlFileForJob(importFile, closer);
            }
            return createNewJob(user, outlineCount, starredCount);
        } catch (Exception e) {
            log.error(MessageFormat.format("Error processing import file {0}", importFile), e);
            return null;
        } finally {
            closeCloser(closer);
        }
    }

    private long processZipFileForJob(File importFile, Closer closer, AtomicInteger starredCount) throws Exception {
        long outlineCount = 0;
        ZipArchiveInputStream archiveInputStream = closer.register(new ZipArchiveInputStream(new FileInputStream(importFile), Charsets.ISO_8859_1.name()));
        ArchiveEntry archiveEntry = archiveInputStream.getNextEntry();
        while (archiveEntry != null) {
            File outputFile = null;
            try {
                if (archiveEntry.getName().endsWith(FILE_SUBSCRIPTIONS_XML)) {
                    outputFile = createTempFile(archiveInputStream, "subscriptions", "xml");
                    outlineCount = readOpmlFileForJob(outputFile);
                } else if (archiveEntry.getName().endsWith(FILE_STARRED_JSON)) {
                    outputFile = createTempFile(archiveInputStream, "starred", "json");
                    readStarredFileForJob(outputFile, starredCount);
                }
            } finally {
                deleteTempFile(outputFile);
            }
            archiveEntry = archiveInputStream.getNextEntry();
        }
        return outlineCount;
    }

    private long processOpmlFileForJob(File importFile, Closer closer) throws Exception {
        InputStream is = closer.register(new FileInputStream(importFile));
        OpmlReader opmlReader = new OpmlReader();
        opmlReader.read(is);
        return getFeedCount(opmlReader.getOutlineList());
    }

    private long readOpmlFileForJob(File outputFile) throws Exception {
        OpmlReader opmlReader = new OpmlReader();
        opmlReader.read(new FileInputStream(outputFile));
        return getFeedCount(opmlReader.getOutlineList());
    }

    private void readStarredFileForJob(File outputFile, AtomicInteger starredCount) throws Exception {
        StarredReader starredReader = new StarredReader();
        starredReader.setStarredArticleListener(event -> starredCount.incrementAndGet());
        starredReader.read(new FileInputStream(outputFile));
    }

    private Job createNewJob(User user, long outlineCount, AtomicInteger starredCount) {
        JobDao jobDao = new JobDao();
        Job job = new Job(user.getId(), Constants.JOB_IMPORT);
        job.setStartDate(new Date());
        jobDao.create(job);

        JobEventDao jobEventDao = new JobEventDao();
        createJobEvent(job, Constants.JOB_EVENT_FEED_COUNT, String.valueOf(outlineCount), jobEventDao);
        createJobEvent(job, Constants.JOB_EVENT_STARRED_ARTICLED_COUNT, String.valueOf(starredCount.get()), jobEventDao);

        return job;
    }

    private void createJobEvent(Job job, String eventType, String message, JobEventDao jobEventDao) {
        JobEvent jobEvent = new JobEvent(job.getId(), eventType, message);
        jobEventDao.create(jobEvent);
    }


    /**
     * Get the total number of feeds in a tree of outlines.
     *
     * @param outlineList List of outlines
     * @return Number of feeds
     */
    private long getFeedCount(List<Outline> outlineList) {
        // Flatten the OPML tree
        Map<String, List<Outline>> outlineMap = OpmlFlattener.flatten(outlineList);

        // Count the total number of feeds
        long feedCount = 0;
        for (List<Outline> categoryOutlineList : outlineMap.values()) {
            feedCount += categoryOutlineList.size();
        }

        return feedCount;
    }

    /**
     * Process the import file.
     *
     * @param user       User
     * @param importFile File to import
     * @param job        Job
     */

    private void processImportFile(final User user, File importFile, final Job job) {
        List<Outline> outlineList = null;
        Closer closer = Closer.create();
        try {
            String mimeType = MimeTypeUtil.guessMimeType(importFile);
            if (MimeType.APPLICATION_ZIP.equals(mimeType)) {
                outlineList = processZipFile(importFile, user, job, closer);
            } else {
                outlineList = processOpmlFile(importFile, closer);
            }

            if (outlineList != null) {
                importOutline(user, outlineList, job);
            }
        } catch (Exception e) {
            log.error(MessageFormat.format("Error processing import file {0}", importFile), e);
        } finally {
            closeCloser(closer);
            deleteImportFile(importFile);
        }
    }

    private List<Outline> processZipFile(File importFile, User user, Job job, Closer closer) throws Exception {
        List<Outline> outlineList = null;
        ZipArchiveInputStream archiveInputStream = closer.register(new ZipArchiveInputStream(new FileInputStream(importFile), Charsets.ISO_8859_1.name()));
        ArchiveEntry archiveEntry = archiveInputStream.getNextEntry();
        while (archiveEntry != null) {
            File outputFile = null;
            try {
                if (archiveEntry.getName().endsWith(FILE_SUBSCRIPTIONS_XML)) {
                    outputFile = createTempFile(archiveInputStream, "subscriptions", "xml");
                    outlineList = readOpmlFile(outputFile);
                } else if (archiveEntry.getName().endsWith(FILE_STARRED_JSON)) {
                    outputFile = createTempFile(archiveInputStream, "starred", "json");
                    readStarredFile(outputFile, user, job);
                }
            } finally {
                deleteTempFile(outputFile);
            }
            archiveEntry = archiveInputStream.getNextEntry();
        }
        return outlineList;
    }

    private List<Outline> processOpmlFile(File importFile, Closer closer) throws Exception {
        InputStream is = closer.register(new FileInputStream(importFile));
        OpmlReader opmlReader = new OpmlReader();
        opmlReader.read(is);
        return opmlReader.getOutlineList();
    }

    private File createTempFile(ZipArchiveInputStream archiveInputStream, String prefix, String suffix) throws IOException {
        File outputFile = File.createTempFile(prefix, suffix);
        ByteStreams.copy(archiveInputStream, new FileOutputStream(outputFile));
        return outputFile;
    }

    private List<Outline> readOpmlFile(File outputFile) throws Exception {
        OpmlReader opmlReader = new OpmlReader();
        opmlReader.read(new FileInputStream(outputFile));
        return opmlReader.getOutlineList();
    }

    private void readStarredFile(File outputFile, User user, Job job) throws Exception {
        StarredReader starredReader = new StarredReader();
        starredReader.setStarredArticleListener(event -> {
            if (log.isInfoEnabled()) {
                log.info(MessageFormat.format("Importing a starred article for user {0}''s import", user.getId()));
            }
            EntityManagerUtil.flush();
            TransactionUtil.commit();
            try {
                importFeedFromStarred(user, event.getFeed(), event.getArticle());
                logJobEvent(job, Constants.JOB_EVENT_STARRED_ARTICLE_IMPORT_SUCCESS, event.getArticle().getTitle());
            } catch (Exception e) {
                log.error(MessageFormat.format("Error importing article {0} from feed {1} for user {2}", event.getArticle(), event.getFeed(), user.getId()), e);
                logJobEvent(job, Constants.JOB_EVENT_STARRED_ARTICLE_IMPORT_FAILURE, event.getArticle().getTitle());
            }
        });
        starredReader.read(new FileInputStream(outputFile));
    }

    private void logJobEvent(Job job, String eventType, String message) {
        JobEventDao jobEventDao = new JobEventDao();
        JobEvent jobEvent = new JobEvent(job.getId(), eventType, message);
        jobEventDao.create(jobEvent);
    }

    private void closeCloser(Closer closer) {
        try {
            closer.close();
        } catch (IOException e) {
            // NOP
        }
    }

    private void deleteImportFile(File importFile) {
        if (importFile != null) {
            importFile.delete();
        }
    }

    private void deleteTempFile(File outputFile) {
        if (outputFile != null) {
            try {
                outputFile.delete();
            } catch (Exception e) {
                // NOP
            }
        }
    }
    /**
     * Import the categories and feeds.
     *
     * @param user        User
     * @param outlineList Outlines to import
     * @param job         Job
     */
    private void importOutline(final User user, final List<Outline> outlineList, final Job job) {
        // Flatten the OPML tree
        Map<String, List<Outline>> outlineMap = OpmlFlattener.flatten(outlineList);

        // Find all user categories
        Map<String, Category> categoryMap = getCategoryMap(user);
        Category rootCategory = getRootCategory(categoryMap);
        int categoryDisplayOrder = categoryMap.size() - 1;

        // Count the total number of feeds
        long feedCount = countTotalFeeds(outlineMap);

        // Create new subscriptions
        createSubscriptions(user, outlineMap, categoryMap, rootCategory, categoryDisplayOrder, feedCount, job);
    }

    private Map<String, Category> getCategoryMap(User user) {
        CategoryDao categoryDao = new CategoryDao();
        List<Category> categoryList = categoryDao.findAllCategory(user.getId());
        Map<String, Category> categoryMap = new HashMap<>();
        for (Category category : categoryList) {
            categoryMap.put(category.getName(), category);
        }
        return categoryMap;
    }

    private Category getRootCategory(Map<String, Category> categoryMap) {
        Category rootCategory = categoryMap.get(null);
        if (rootCategory == null) {
            throw new RuntimeException("Root category not found");
        }
        return rootCategory;
    }

    private long countTotalFeeds(Map<String, List<Outline>> outlineMap) {
        long feedCount = 0;
        for (List<Outline> categoryOutlineList : outlineMap.values()) {
            feedCount += categoryOutlineList.size();
        }
        return feedCount;
    }

    private void createSubscriptions(User user, Map<String, List<Outline>> outlineMap, Map<String, Category> categoryMap, Category rootCategory, int categoryDisplayOrder, long feedCount, Job job) {
        int i = 0;
        final FeedSubscriptionDao feedSubscriptionDao = new FeedSubscriptionDao();
        final JobEventDao jobEventDao = new JobEventDao();
        for (Entry<String, List<Outline>> entry : outlineMap.entrySet()) {
            String categoryName = entry.getKey();
            List<Outline> categoryOutlineList = entry.getValue();

            // Create a new category if necessary
            Category category = categoryMap.get(categoryName);
            Integer feedDisplayOrder = 0;
            if (category == null) {
                category = createNewCategory(user, rootCategory, categoryName, categoryDisplayOrder);
                categoryMap.put(categoryName, category);
                categoryDisplayOrder++;
            } else {
                feedDisplayOrder = feedSubscriptionDao.getCategoryCount(category.getId(), user.getId());
            }

            // Create the subscriptions
            for (int j = 0; j < categoryOutlineList.size(); j++) {
                EntityManagerUtil.flush();
                TransactionUtil.commit();
                if (log.isInfoEnabled()) {
                    log.info(MessageFormat.format("Importing outline {0}/{1}", i + j + 1, feedCount));
                }
                Outline outline = categoryOutlineList.get(j);
                String feedTitle = !Strings.isNullOrEmpty(outline.getText()) ? outline.getText() : outline.getTitle();
                String feedUrl = outline.getXmlUrl();

                // Check if the user is already subscribed to this feed
                if (isUserSubscribedToFeed(user, feedUrl)) {
                    logSubscriptionSuccess(job, feedUrl);
                    continue;
                }

                // Synchronize feed and articles
                Feed feed = synchronizeFeed(feedUrl, user, job);
                if (feed == null) continue;

                // Create the subscription if the feed can be synchronized
                createFeedSubscription(user, category, feedDisplayOrder, feedTitle, feed, job);
                feedDisplayOrder++;
            }
            i += categoryOutlineList.size();
        }
    }

    private Category createNewCategory(User user, Category rootCategory, String categoryName, int categoryDisplayOrder) {
        CategoryDao categoryDao = new CategoryDao();
        Category category = new Category();
        category.setUserId(user.getId());
        category.setParentId(rootCategory.getId());
        category.setName(categoryName);
        category.setOrder(categoryDisplayOrder);
        categoryDao.create(category);
        return category;
    }

    private boolean isUserSubscribedToFeed(User user, String feedUrl) {
        FeedSubscriptionDao feedSubscriptionDao = new FeedSubscriptionDao();
        FeedSubscriptionCriteria feedSubscriptionCriteria = new FeedSubscriptionCriteria()
                .setUserId(user.getId())
                .setFeedUrl(feedUrl);
        List<FeedSubscriptionDto> feedSubscriptionList = feedSubscriptionDao.findByCriteria(feedSubscriptionCriteria);
        return !feedSubscriptionList.isEmpty();
    }

    private void logSubscriptionSuccess(Job job, String feedUrl) {
        JobEventDao jobEventDao = new JobEventDao();
        JobEvent jobEvent = new JobEvent(job.getId(), Constants.JOB_EVENT_FEED_IMPORT_SUCCESS, feedUrl);
        jobEventDao.create(jobEvent);
    }

    private Feed synchronizeFeed(String feedUrl, User user, Job job) {
        final FeedService feedService = AppContext.getInstance().getFeedService();
        try {
            return feedService.synchronize(feedUrl);
        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error(MessageFormat.format("Error importing the feed at URL {0} for user {1}", feedUrl, user.getId()), e);
            }
            logSubscriptionFailure(job, feedUrl);
            return null;
        }
    }

    private void logSubscriptionFailure(Job job, String feedUrl) {
        JobEventDao jobEventDao = new JobEventDao();
        JobEvent jobEvent = new JobEvent(job.getId(), Constants.JOB_EVENT_FEED_IMPORT_FAILURE, feedUrl);
        jobEventDao.create(jobEvent);
    }

    private void createFeedSubscription(User user, Category category, Integer feedDisplayOrder, String feedTitle, Feed feed, Job job) {
        FeedSubscriptionDao feedSubscriptionDao = new FeedSubscriptionDao();
        final FeedService feedService = AppContext.getInstance().getFeedService();
        try {
            FeedSubscription feedSubscription = new FeedSubscription();
            feedSubscription.setUserId(user.getId());
            feedSubscription.setFeedId(feed.getId());
            feedSubscription.setCategoryId(category.getId());
            feedSubscription.setOrder(feedDisplayOrder);
            feedSubscription.setUnreadCount(0);
            feedSubscription.setTitle(feedTitle);
            feedSubscriptionDao.create(feedSubscription);

            // Create the initial article subscriptions for this user
            EntityManagerUtil.flush();
            feedService.createInitialUserArticle(user.getId(), feedSubscription);

            logSubscriptionSuccess(job, feed.getRssUrl());
        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error(MessageFormat.format("Error creating the subscription to the feed at URL {0} for user {1}", feed.getRssUrl(), user.getId()), e);
            }
            logSubscriptionFailure(job, feed.getRssUrl());
        }
    }
    /**
     * Create the feeds referenced from starred articles.
     * If some feed cannot be downloaded, a record is still created from the export data only.
     *
     * @param user User
     * @param feed Feed to import
     */

// SubscriptionImportAsyncListener.java
    private void importFeedFromStarred(User user, Feed feed, Article article) {
        String rssUrl = feed.getRssUrl();
        FeedDao feedDao = new FeedDao();
        final FeedService feedService = AppContext.getInstance().getFeedService();
        final Logger log = LoggerFactory.getLogger(SubscriptionImportAsyncListener.class);

        // Synchronize or create the feed
        Feed feedFromDb = synchronizeOrCreateFeed(rssUrl, feedService, feedDao, log, user.getId());

        // Find or create the article
        ArticleDao articleDao = new ArticleDao();
        article = findOrCreateArticle(article, feedFromDb, articleDao, log);

        // Check if the user is already subscribed to this article
        subscribeUserToArticle(user, article);
    }

    private Feed synchronizeOrCreateFeed(String rssUrl, FeedService feedService, FeedDao feedDao, Logger log, String userId) {
        Feed feedFromDb = feedDao.getByRssUrl(rssUrl);
        if (feedFromDb == null) {
            try {
                feedFromDb = feedService.synchronize(rssUrl);
            } catch (Exception e) {
                if (log.isInfoEnabled()) {
                    log.info(MessageFormat.format("Error importing the feed at URL {0} for user {1}'s starred articles. Maybe it doesn't exist anymore?", rssUrl, userId), e);
                }
                feedFromDb = new Feed();
                feedFromDb.setUrl(rssUrl);
                feedFromDb.setRssUrl(rssUrl);
                feedFromDb.setTitle(StringUtils.abbreviate(rssUrl, 100));
                feedDao.create(feedFromDb);
            }
        }
        return feedFromDb;
    }

    private Article findOrCreateArticle(Article article, Feed feed, ArticleDao articleDao, Logger log) {
        ArticleCriteria articleCriteria = new ArticleCriteria()
                .setTitle(article.getTitle())
                .setUrl(article.getUrl())
                .setFeedId(feed.getId());

        List<ArticleDto> currentArticleList = articleDao.findByCriteria(articleCriteria);
        if (!currentArticleList.isEmpty()) {
            article.setId(currentArticleList.iterator().next().getId());
        } else {
            article.setFeedId(feed.getId());
            GuidFixer.fixGuid(article);
            articleDao.create(article);
        }
        return article;
    }

    private void subscribeUserToArticle(User user, Article article) {
        UserArticleCriteria userArticleCriteria = new UserArticleCriteria()
                .setUserId(user.getId())
                .setArticleId(article.getId());

        UserArticleDao userArticleDao = new UserArticleDao();
        List<UserArticleDto> userArticleList = userArticleDao.findByCriteria(userArticleCriteria);
        UserArticleDto currentUserArticle = null;
        if (userArticleList.size() > 0) {
            currentUserArticle = userArticleList.iterator().next();
        }
        if (currentUserArticle == null || currentUserArticle.getId() == null) {
            // Subscribe the user to this article
            UserArticle userArticle = new UserArticle();
            userArticle.setUserId(user.getId());
            userArticle.setArticleId(article.getId());
            userArticle.setStarredDate(article.getPublicationDate());
            userArticle.setReadDate(article.getPublicationDate());
            userArticleDao.create(userArticle);
        } else if (currentUserArticle.getId() != null && currentUserArticle.getStarTimestamp() == null) {
            // Mark the user article as starred
            UserArticle userArticle = new UserArticle();
            userArticle.setId(currentUserArticle.getId());
            userArticle.setStarredDate(article.getPublicationDate());
            userArticle.setReadDate(article.getPublicationDate());
            userArticleDao.update(userArticle);
        }
    }
}