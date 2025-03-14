package com.sismics.reader.rest.resource;

import com.sismics.reader.core.dao.jpa.CategoryDao;
import com.sismics.reader.core.dao.jpa.FeedSubscriptionDao;
import com.sismics.reader.core.dao.jpa.UserArticleDao;
import com.sismics.reader.core.dao.jpa.criteria.FeedSubscriptionCriteria;
import com.sismics.reader.core.dao.jpa.criteria.UserArticleCriteria;
import com.sismics.reader.core.dao.jpa.dto.FeedSubscriptionDto;
import com.sismics.reader.core.dao.jpa.dto.UserArticleDto;
import com.sismics.reader.core.model.jpa.Category;
import com.sismics.reader.core.model.jpa.FeedSubscription;
import com.sismics.reader.core.util.jpa.PaginatedList;
import com.sismics.reader.core.util.jpa.PaginatedLists;
import com.sismics.reader.rest.assembler.ArticleAssembler;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.util.ValidationUtil;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import javax.persistence.NoResultException;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Category REST resources.
 *
 * Uses Iterator Pattern for hierarchical category retrieval.
 */
@Path("/category")
public class CategoryResource extends BaseResource {
    /**
     * Returns all categories using the Iterator Pattern.
     *
     * @return Response
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        CategoryDao categoryDao = new CategoryDao();
        // Get the root category
        Category rootCategory = categoryDao.getRootCategory(principal.getId());

        // Create root category JSON object
        JSONObject rootCategoryJson = new JSONObject();
        rootCategoryJson.put("id", rootCategory.getId());
        rootCategoryJson.put("name", rootCategory.getName());
        rootCategoryJson.put("folded", rootCategory.isFolded());
        rootCategoryJson.put("categories", new JSONArray());

        // Use iterator to traverse category hierarchy
        List<Category> rootCategories = new ArrayList<>();
        rootCategories.add(rootCategory);
        CategoryIterator iterator = new CategoryHierarchyIterator(rootCategories, principal.getId());

        // Build category hierarchy using the existing method
        JSONArray subCategoriesList = new JSONArray();
        addSubCategories(subCategoriesList, rootCategory.getId(), categoryDao, principal.getId(), rootCategory.getName());

        // Attach hierarchy to root category
        rootCategoryJson.put("categories", subCategoriesList);

        // Construct final response
        JSONArray categoryList = new JSONArray();
        categoryList.put(rootCategoryJson);

        JSONObject response = new JSONObject();
        response.put("categories", categoryList);

        return Response.ok().entity(response.toString()).build();
    }

    private void addSubCategories(JSONArray categoryList, String parentId, CategoryDao categoryDao, String userId, String parentPath) throws JSONException {
        List<Category> subCategories = categoryDao.findSubCategory(parentId, userId);

        for (Category subCategory : subCategories) {
            // Construct full path name (e.g., A/B, A/B/C)
            String fullPath = (parentPath==null?"":parentPath+ "/")  + subCategory.getName();

            JSONObject categoryJson = new JSONObject();
            categoryJson.put("id", subCategory.getId());
            categoryJson.put("name", fullPath);
            categoryJson.put("folded", subCategory.isFolded());



            // Add to the flat list
            categoryList.put(categoryJson);

            // Recursive call, passing the full path
            addSubCategories(categoryList, subCategory.getId(), categoryDao, userId, fullPath);
        }
    }
    /**
     * Returns all articles in a category.
     *
     * @param id Category ID
     * @param unread Returns only unread articles
     * @param limit Page limit
     * @param afterArticle Start the list after this article
     * @return Response
     */
    @GET
    @Path("{id: [a-z0-9\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(
            @PathParam("id") String id,
            @QueryParam("unread") boolean unread,
            @QueryParam("limit") Integer limit,
            @QueryParam("after_article") String afterArticle) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the category
        CategoryDao categoryDao = new CategoryDao();
        Category category;
        try {
            category = categoryDao.getCategory(id, principal.getId());
        } catch (NoResultException e) {
            throw new ClientException("CategoryNotFound", MessageFormat.format("Category not found: {0}", id));
        }

        // Get the articles
        UserArticleDao userArticleDao = new UserArticleDao();
        UserArticleCriteria userArticleCriteria = new UserArticleCriteria()
                .setUnread(unread)
                .setUserId(principal.getId())
                .setSubscribed(true)
                .setVisible(true);
        if (category.getParentId() != null) {
            userArticleCriteria.setCategoryId(id);
        }
        if (afterArticle != null) {
            // Paginate after this user article
            UserArticleCriteria afterArticleCriteria = new UserArticleCriteria()
                    .setUserArticleId(afterArticle)
                    .setUserId(principal.getId());
            List<UserArticleDto> userArticleDtoList = userArticleDao.findByCriteria(afterArticleCriteria);
            if (userArticleDtoList.isEmpty()) {
                throw new ClientException("ArticleNotFound", MessageFormat.format("Can't find user article {0}", afterArticle));
            }
            UserArticleDto userArticleDto = userArticleDtoList.iterator().next();

            userArticleCriteria.setArticlePublicationDateMax(new Date(userArticleDto.getArticlePublicationTimestamp()));
            userArticleCriteria.setArticleIdMax(userArticleDto.getArticleId());
        }

        PaginatedList<UserArticleDto> paginatedList = PaginatedLists.create(limit, null);
        userArticleDao.findByCriteria(paginatedList, userArticleCriteria, null, null);

        // Build the response
        JSONObject response = new JSONObject();

        List<JSONObject> articles = new ArrayList<JSONObject>();
        for (UserArticleDto userArticle : paginatedList.getResultList()) {
            articles.add(ArticleAssembler.asJson(userArticle));
        }
        response.put("articles", articles);

        return Response.ok().entity(response).build();
    }

    /**
     * Creates a new category.
     *
     * @param name Category name
     * @return Response
     */
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    public Response add(
            @FormParam("name") String name, @FormParam("parentId") String parentId) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        System.out.println(parentId);
        // Validate input data
        name = ValidationUtil.validateLength(name, "name", 1, 100, false);

        // Get the root category
        CategoryDao categoryDao = new CategoryDao();
        Category rootCategory = categoryDao.getRootCategory(principal.getId());

        // Get the display order
        int displayOrder = categoryDao.getCategoryCount(rootCategory.getId(), principal.getId());

        // Create the category
        Category category = new Category();
        category.setUserId(principal.getId());
        if(parentId!=null) category.setParentId(parentId);
        else category.setParentId(rootCategory.getId());
        category.setName(name);
        category.setOrder(displayOrder);
        String categoryId = categoryDao.create(category);

        JSONObject response = new JSONObject();
        response.put("id", categoryId);
        return Response.ok().entity(response).build();
    }

    /**
     * Deletes a category.
     *
     * @param id Category ID
     * @return Response
     */
    @DELETE
    @Path("{id: [a-z0-9\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(
            @PathParam("id") String id) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the category
        CategoryDao categoryDao = new CategoryDao();
        try {
            categoryDao.getCategory(id, principal.getId());
        } catch (NoResultException e) {
            throw new ClientException("CategoryNotFound", MessageFormat.format("Category not found: {0}", id));
        }

        // Move subscriptions in this category to root
        FeedSubscriptionDao feedSubscriptionDao = new FeedSubscriptionDao();
        List<FeedSubscription> feedSubscriptionList = feedSubscriptionDao.findByCategory(id);
        Category rootCategory = categoryDao.getRootCategory(principal.getId());
        for (FeedSubscription feedSubscription : feedSubscriptionList) {
            feedSubscription.setCategoryId(rootCategory.getId());
            feedSubscriptionDao.update(feedSubscription);
            feedSubscriptionDao.reorder(feedSubscription, 0);
        }

        // Delete the category
        categoryDao.delete(id);

        // Always return ok
        JSONObject response = new JSONObject();
        response.put("status", "ok");
        return Response.ok().entity(response).build();
    }

    /**
     * Marks all articles in this category as read.
     *
     * @param id Category ID
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/read")
    @Produces(MediaType.APPLICATION_JSON)
    public Response read(
            @PathParam("id") String id) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the category
        Category category;
        try {
            category = new CategoryDao().getCategory(id, principal.getId());
        } catch (NoResultException e) {
            throw new ClientException("CategoryNotFound", MessageFormat.format("Category not found: {0}", id));
        }

        // Marks all articles as read in this category
        UserArticleDao userArticleDao = new UserArticleDao();
        userArticleDao.markAsRead(new UserArticleCriteria()
                .setUserId(principal.getId())
                .setSubscribed(true)
                .setCategoryId(id));

        FeedSubscriptionDao feedSubscriptionDao = new FeedSubscriptionDao();
        for (FeedSubscriptionDto feedSubscrition : feedSubscriptionDao.findByCriteria(new FeedSubscriptionCriteria()
                .setCategoryId(category.getId())
                .setUserId(principal.getId()))) {
            feedSubscriptionDao.updateUnreadCount(feedSubscrition.getId(), 0);
        }

        // Always return ok
        JSONObject response = new JSONObject();
        response.put("status", "ok");
        return Response.ok().entity(response).build();
    }

    /**
     * Updates the category.
     *
     * @param id Category ID
     * @param name Category name
     * @param order Display order of this category
     * @param folded True if this category is folded in the subscriptions tree.
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(
            @PathParam("id") String id,
            @FormParam("name") String name,
            @FormParam("order") Integer order,
            @FormParam("folded") Boolean folded) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Validate input data
        name = ValidationUtil.validateLength(name, "name", 1, 100, true);

        // Get the category
        CategoryDao categoryDao = new CategoryDao();
        Category category;
        try {
            category = categoryDao.getCategory(id, principal.getId());
        } catch (NoResultException e) {
            throw new ClientException("CategoryNotFound", MessageFormat.format("Category not found: {0}", id));
        }

        // Update the category
        if (name != null) {
            category.setName(name);
        }
        if (folded != null) {
            category.setFolded(folded);
        }
        categoryDao.update(category);

        // Reorder categories
        if (order != null) {
            categoryDao.reorder(category, order);
        }

        // Always return ok
        JSONObject response = new JSONObject();
        response.put("status", "ok");
        return Response.ok().entity(response).build();
    }
}
