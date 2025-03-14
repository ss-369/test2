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

import java.util.Iterator;
import java.util.List;
import java.util.Stack;
import com.sismics.reader.core.model.jpa.Category;

public class CategoryHierarchyIterator implements CategoryIterator {
    private Stack<Iterator<Category>> stack = new Stack<>();
    private CategoryDao categoryDao;
    private String userId;

    public CategoryHierarchyIterator(List<Category> rootCategories, String userId) {
        this.stack.push(rootCategories.iterator());
        this.categoryDao = new CategoryDao();
        this.userId = userId;
    }

    @Override
    public boolean hasNext() {
        while (!stack.isEmpty()) {
            Iterator<Category> iterator = stack.peek();
            if (iterator.hasNext()) {
                return true;
            } else {
                stack.pop(); // Move up the hierarchy
            }
        }
        return false;
    }

    @Override
    public Category next() {
        if (hasNext()) {
            Category nextCategory = stack.peek().next();

            // ✅ Fetch subcategories using CategoryDao (Fix for "getSubCategories()" error)
            List<Category> subCategories = categoryDao.findSubCategory(nextCategory.getId(), userId);

            if (subCategories != null && !subCategories.isEmpty()) {
                stack.push(subCategories.iterator()); // Add next level of hierarchy
            }
            return nextCategory;
        }
        throw new IllegalStateException("No more categories.");
    }
}
