/**
 * Copyright 2017 Pogeyan Technologies
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.pogeyan.cmis.impl.services;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderContainerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectParentDataImpl;
import org.apache.chemistry.opencmis.commons.impl.server.ObjectInfoImpl;
import org.apache.chemistry.opencmis.commons.server.ObjectInfoHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pogeyan.cmis.api.auth.IUserObject;
import com.pogeyan.cmis.api.data.IBaseObject;
import com.pogeyan.cmis.api.data.IDocumentObject;
import com.pogeyan.cmis.api.data.common.AccessControlListImplExt;
import com.pogeyan.cmis.api.data.services.MBaseObjectDAO;
import com.pogeyan.cmis.api.data.services.MDocumentObjectDAO;
import com.pogeyan.cmis.api.data.services.MNavigationDocServiceDAO;
import com.pogeyan.cmis.api.data.services.MNavigationServiceDAO;
import com.pogeyan.cmis.api.data.services.MTypeManagerDAO;
import com.pogeyan.cmis.api.utils.Helpers;
import com.pogeyan.cmis.impl.factory.DatabaseServiceFactory;
import com.pogeyan.cmis.impl.utils.DBUtils;

public class CmisNavigationService {
	private static final Logger LOG = LoggerFactory.getLogger(CmisNavigationService.class);

	public static class Impl {
		/**
		 * Method to gets children list only the direct containers of a folder.
		 */
		public static ObjectInFolderList getChildren(String repositoryId, String folderId, String filter,
				String orderBy, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
				String renditionFilter, Boolean includePathSegment, BigInteger maxItems, BigInteger skipCount,
				ObjectInfoHandler objectInfos, IUserObject userObject, String typeId)
				throws CmisObjectNotFoundException {
			int maxItemsInt = maxItems == null ? -1 : maxItems.intValue();
			int skipCountInt = skipCount == null ? 0 : skipCount.intValue();
			ObjectInFolderList res = getChildrenIntern(repositoryId, folderId, filter, orderBy, includeAllowableActions,
					includeRelationships, renditionFilter, includePathSegment, maxItemsInt, skipCountInt, false, false,
					objectInfos, userObject, typeId);
			if (res != null) {
				LOG.debug("getChildren result for folderId: {}, numItems: {}", folderId, res.getNumItems());
			}
			return res;
		}

		/**
		 * Return the children list in the format of ObjectInFolderList.
		 */
		private static ObjectInFolderList getChildrenIntern(String repositoryId, String folderId, String filter,
				String orderBy, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
				String renditionFilter, Boolean includePathSegments, int maxItems, int skipCount, boolean folderOnly,
				boolean includePwc, ObjectInfoHandler objectInfos, IUserObject userObject, String typeId)
				throws CmisObjectNotFoundException {
			ObjectInFolderListImpl result = new ObjectInFolderListImpl();
			List<ObjectInFolderData> folderList = new ArrayList<ObjectInFolderData>();
			MNavigationDocServiceDAO navigationMorphiaDAO = DatabaseServiceFactory.getInstance(repositoryId)
					.getObjectService(repositoryId, MNavigationDocServiceDAO.class);
			MTypeManagerDAO typeManagerDAO = DatabaseServiceFactory.getInstance(repositoryId)
					.getObjectService(repositoryId, MTypeManagerDAO.class);
			IBaseObject data = DBUtils.BaseDAO.getByObjectId(repositoryId, folderId, null, typeId);
			if (data == null) {
				LOG.error("getChildrenIntern unknown object id: {}, repository: {}", folderId, repositoryId);
				throw new CmisObjectNotFoundException("Unknown object id: " + folderId);
			}

			String[] filterArray = new String[] {};
			// split filter
			Set<String> filterCollection = Helpers.splitFilter(filter);
			if (filter != null && filterCollection != null && filterCollection.size() > 0) {
				filterArray = Helpers.getFilterArray(filterCollection, true);
			}
			String path = null;
			String[] principalIds = com.pogeyan.cmis.api.utils.Helpers.getPrincipalIds(userObject);
			List<? extends IDocumentObject> children = new ArrayList<>();
			long childrenCount = 0;
			if (orderBy != null) {
				String[] orderQuery = orderBy.split(",");
				orderBy = Arrays.stream(orderQuery).map(t -> getOrderByName(t)).collect(Collectors.joining(","));
			}
			if (data.getName().equalsIgnoreCase("@ROOT@")) {
				path = "," + data.getId() + ",";
				children = navigationMorphiaDAO.getChildren(path, principalIds, true, maxItems, skipCount, orderBy,
						filterArray, Helpers.splitFilterQuery(filter), typeManagerDAO, repositoryId, typeId);
				childrenCount = navigationMorphiaDAO.getChildrenSize(path, principalIds, true, repositoryId, typeId);
			} else {
				path = data.getInternalPath() + folderId + ",";
				List<AccessControlListImplExt> mAcl = getParentAcl(repositoryId, data.getInternalPath(), data.getAcl(),
						typeId);
				boolean objectOnly = true;
				if (mAcl != null && mAcl.size() > 0) {
					for (AccessControlListImplExt acl : mAcl) {
						if (acl != null) {
							if (acl.getAclPropagation().equalsIgnoreCase("PROPAGATE")) {
								List<Ace> listAce = acl.getAces().stream()
										.filter(t -> Arrays.stream(principalIds).parallel()
												.anyMatch(x -> Objects.equals(x.toLowerCase(),
														t.getPrincipalId().toLowerCase())) == true)
										.collect(Collectors.toList());
								if (listAce.size() >= 1) {
									children = navigationMorphiaDAO.getChildren(path, principalIds, false, maxItems,
											skipCount, orderBy, filterArray, Helpers.splitFilterQuery(filter),
											typeManagerDAO, repositoryId, typeId);
									childrenCount = navigationMorphiaDAO.getChildrenSize(path, principalIds, false,
											repositoryId, typeId);
									objectOnly = false;
									break;
								}
							} else if (acl.getAclPropagation().equalsIgnoreCase("REPOSITORYDETERMINED")) {
								objectOnly = true;
							}
						}
					}
				}
				// Acl Propagation ObjectOnly
				if (objectOnly) {
					children = navigationMorphiaDAO.getChildren(path, principalIds, true, maxItems, skipCount, orderBy,
							filterArray, Helpers.splitFilterQuery(filter), typeManagerDAO, repositoryId, typeId);
					childrenCount = navigationMorphiaDAO.getChildrenSize(path, principalIds, true, repositoryId,
							typeId);
				}
			}

			for (IDocumentObject child : children) {
				ObjectInFolderDataImpl oifd = new ObjectInFolderDataImpl();
				if (includePathSegments != null && includePathSegments) {
					oifd.setPathSegment(child.getName());
				}

				ObjectData objectData = CmisObjectService.Impl.compileObjectData(repositoryId, child, filterCollection,
						includeAllowableActions, false, true, objectInfos, renditionFilter, includeRelationships,
						userObject);
				oifd.setObject(objectData);
				folderList.add(oifd);
				if (objectInfos != null) {
					ObjectInfoImpl objectInfo = new ObjectInfoImpl();
					objectInfos.addObjectInfo(objectInfo);
				}
			}

			result.setObjects(folderList);
			result.setNumItems(BigInteger.valueOf(childrenCount));
			result.setHasMoreItems(skipCount + 1 * maxItems < childrenCount);

			return result;
		}

		/**
		 * Gets the all descendants containees of a folder and all of their children to
		 * a specified depth
		 */
		public static List<ObjectInFolderContainer> getDescendants(String repositoryId, String folderId,
				BigInteger depth, String filter, Boolean includeAllowableActions,
				IncludeRelationships includeRelationships, String renditionFilter, Boolean includePathSegment,
				ObjectInfoHandler objectInfos, IUserObject userObject, String typeId)
				throws CmisInvalidArgumentException {
			int levels = 0;
			if (depth == null) {
				levels = 2; // one of the recommended defaults (should it be
			} else if (depth.intValue() == 0) {
				LOG.error("getDescendants a zero depth is not allowed for getDescendants: {}, repository: {}", folderId,
						repositoryId);
				throw new CmisInvalidArgumentException("A zero depth is not allowed for getDescendants().");
			} else {
				levels = depth.intValue();
			}
			List<ObjectInFolderContainer> result = null;
			String[] principalIds = com.pogeyan.cmis.api.utils.Helpers.getPrincipalIds(userObject);
			IBaseObject data = DBUtils.BaseDAO.getByObjectId(repositoryId, folderId, null, typeId);
			if (data != null) {
				if (data.getBaseId().equals(BaseTypeId.CMIS_FOLDER)) {
					int level = 0;
					result = getDescendantsIntern(repositoryId, folderId, filter, includeAllowableActions,
							includeRelationships, renditionFilter, includePathSegment, level, levels, false,
							objectInfos, userObject, typeId);
				} else {
					int level = 0;
					ObjectInFolderContainerImpl oifc = new ObjectInFolderContainerImpl();
					ObjectInFolderDataImpl oifd = new ObjectInFolderDataImpl();
					Set<String> filterCollection = Helpers.splitFilter(filter);
					ObjectData objectData = CmisObjectService.Impl.compileObjectData(repositoryId, data,
							filterCollection, includeAllowableActions, true, true, objectInfos, renditionFilter,
							includeRelationships, userObject);
					oifd.setObject(objectData);
					boolean acl = false;
					if (data.getAcl() != null && data.getAcl().getAces().size() > 0) {
						if (data.getAcl() != null) {
							if (data.getAcl().getAclPropagation().equalsIgnoreCase("REPOSITORYDETERMINED")) {
								acl = true;
							} else {
								List<Ace> listAce = data.getAcl().getAces().stream()
										.filter(t -> Arrays.stream(principalIds).parallel()
												.anyMatch(x -> Objects.equals(x.toLowerCase(),
														t.getPrincipalId().toLowerCase())) == true)
										.collect(Collectors.toList());
								if (listAce.size() >= 1) {
									acl = true;
								}
							}
						}
					}
					if (acl) {
						result = getDescendantsRelationObjects(repositoryId, folderId, filter, includeAllowableActions,
								includeRelationships, renditionFilter, includePathSegment, level, levels, false,
								objectInfos, userObject, typeId);
						oifc.setObject(oifd);
						oifc.setChildren(result);
						List<ObjectInFolderContainer> parentData = new ArrayList<ObjectInFolderContainer>();
						parentData.add(oifc);
						result = parentData;
					}

				}
			}
			if (result != null)

			{
				LOG.debug("getDescendants result for folderId: {}, numItems: {}", folderId, result.size());
			}
			return result;
		}

		/**
		 * Return the descendants list in the format of ObjectInFolderContainer.
		 */
		private static List<ObjectInFolderContainer> getDescendantsIntern(String repositoryId, String folderId,
				String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
				String renditionFilter, Boolean includePathSegments, int level, int maxLevels, boolean folderOnly,
				ObjectInfoHandler objectInfos, IUserObject userObject, String typeId) {
			MNavigationDocServiceDAO navigationMorphiaDAO = DatabaseServiceFactory.getInstance(repositoryId)
					.getObjectService(repositoryId, MNavigationDocServiceDAO.class);
			MTypeManagerDAO typeManagerDAO = DatabaseServiceFactory.getInstance(repositoryId)
					.getObjectService(repositoryId, MTypeManagerDAO.class);
			IBaseObject data = DBUtils.BaseDAO.getByObjectId(repositoryId, folderId, null, typeId);
			String[] filterArray = new String[] {};
			// split filter
			Set<String> filterCollection = Helpers.splitFilter(filter);
			if (filter != null && filterCollection != null && filterCollection.size() > 0) {
				filterArray = Helpers.getFilterArray(filterCollection, true);
			}
			String path = "," + folderId + ",";
			List<AccessControlListImplExt> mAcl = getParentAcl(repositoryId, data.getInternalPath(), data.getAcl(),
					typeId);

			List<? extends IBaseObject> children = new ArrayList<>();
			String[] principalIds = com.pogeyan.cmis.api.utils.Helpers.getPrincipalIds(userObject);
			boolean objectOnly = true;
			if (mAcl != null && mAcl.size() > 0) {
				for (AccessControlListImplExt acl : mAcl) {
					if (acl != null) {
						if (acl.getAclPropagation().equalsIgnoreCase("PROPAGATE")) {
							List<Ace> listAce = acl.getAces().stream()
									.filter(t -> Arrays.stream(principalIds).parallel()
											.anyMatch(x -> Objects.equals(x.toLowerCase(),
													t.getPrincipalId().toLowerCase())) == true)
									.collect(Collectors.toList());
							if (listAce.size() >= 1) {
								children = navigationMorphiaDAO.getDescendants(path, principalIds, false, filterArray,
										Helpers.splitFilterQuery(filter), typeManagerDAO);
								objectOnly = false;
								break;
							}
						} else if (acl.getAclPropagation().equalsIgnoreCase("REPOSITORYDETERMINED")) {
							objectOnly = true;
						}
					}
				}
			}
			// Acl Propagation ObjectOnly
			if (objectOnly) {
				children = navigationMorphiaDAO.getDescendants(path, principalIds, true, filterArray,
						Helpers.splitFilterQuery(filter), typeManagerDAO);
			}
			List<String> listOfParentIds = new ArrayList<>();
			List<ObjectInFolderContainer> childrenOfFolderId = new ArrayList<ObjectInFolderContainer>();
			childrenOfFolderId = getDescendants(repositoryId, children, folderId, includePathSegments, filter,
					includeAllowableActions, objectInfos, renditionFilter, includeRelationships, userObject,
					listOfParentIds);
			childrenOfFolderId = getDifferenceDescendants(repositoryId, children, folderId, includePathSegments, filter,
					includeAllowableActions, objectInfos, renditionFilter, includeRelationships, userObject,
					listOfParentIds, childrenOfFolderId);
			return childrenOfFolderId;
		}

		private static List<ObjectInFolderContainer> getDescendants(String repositoryId,
				List<? extends IBaseObject> children, String folderId, Boolean includePathSegments, String filter,
				Boolean includeAllowableActions, ObjectInfoHandler objectInfos, String renditionFilter,
				IncludeRelationships includeRelationships, IUserObject userObject, List<String> listOfParentIds) {
			LOG.debug("getDescendants child object data: {}", children);
			List<ObjectInFolderContainer> childrenOfFolderId = new ArrayList<ObjectInFolderContainer>();
			ObjectInFolderList childList = getChildernList(repositoryId, children, folderId, includePathSegments,
					filter, includeAllowableActions, objectInfos, renditionFilter, includeRelationships, userObject,
					listOfParentIds);
			if (0 != childList.getObjects().size()) {
				for (ObjectInFolderData child : childList.getObjects()) {
					ObjectInFolderContainerImpl oifc = new ObjectInFolderContainerImpl();
					String childId = child.getObject().getId();
					listOfParentIds.add(childId);
					List<ObjectInFolderContainer> subChildren = getDescendants(repositoryId, children, childId,
							includePathSegments, filter, includeAllowableActions, objectInfos, renditionFilter,
							includeRelationships, userObject, listOfParentIds);
					oifc.setObject(child);
					if (0 != subChildren.size()) {
						oifc.setChildren(subChildren);
					}
					childrenOfFolderId.add(oifc);
				}
			}
			return childrenOfFolderId;
		}

		private static List<ObjectInFolderContainer> getDifferenceDescendants(String repositoryId,
				List<? extends IBaseObject> children, String folderId, Boolean includePathSegments, String filter,
				Boolean includeAllowableActions, ObjectInfoHandler objectInfos, String renditionFilter,
				IncludeRelationships includeRelationships, IUserObject userObject, List<String> listOfParentIds,
				List<ObjectInFolderContainer> childrenOfFolderId) {
			if (listOfParentIds.isEmpty()) {
				if (children.size() > 0) {
					ObjectInFolderList childLists = getChildernList(repositoryId, children, null, includePathSegments,
							filter, includeAllowableActions, objectInfos, renditionFilter, includeRelationships,
							userObject, listOfParentIds);
					for (ObjectInFolderData child : childLists.getObjects()) {
						ObjectInFolderContainerImpl oifc = new ObjectInFolderContainerImpl();
						oifc.setObject(child);
						childrenOfFolderId.add(oifc);
					}
				}
			} else {
				List<String> childrenIds = children.stream().map(s -> s.getId()).collect(Collectors.toList());
				List<String> union = new ArrayList<String>(childrenIds);
				union.addAll(listOfParentIds);
				List<String> intersection = new ArrayList<String>(childrenIds);
				intersection.retainAll(listOfParentIds);
				List<String> diff = new ArrayList<String>(union);
				diff.removeAll(intersection);
				if (diff.size() > 0) {
					for (String id : diff) {
						Map<String, IBaseObject> childrenMap = children.stream()
								.collect(Collectors.toMap(IBaseObject::getId, c -> c));
						ObjectInFolderList childLists = getChildernList(repositoryId,
								Arrays.asList(childrenMap.get(id)), null, includePathSegments, filter,
								includeAllowableActions, objectInfos, renditionFilter, includeRelationships, userObject,
								listOfParentIds);
						for (ObjectInFolderData child : childLists.getObjects()) {
							ObjectInFolderContainerImpl oifc = new ObjectInFolderContainerImpl();
							oifc.setObject(child);
							childrenOfFolderId.add(oifc);
						}
					}
				}

			}
			return childrenOfFolderId;
		}

		private static ObjectInFolderList getChildernList(String repositoryId, List<? extends IBaseObject> children,
				String folderId, Boolean includePathSegments, String filter, Boolean includeAllowableActions,
				ObjectInfoHandler objectInfos, String renditionFilter, IncludeRelationships includeRelationships,
				IUserObject userObject, List<String> listOfParentIds) {
			List<ObjectInFolderData> folderList = new ArrayList<ObjectInFolderData>();
			ObjectInFolderListImpl result = new ObjectInFolderListImpl();
			for (IBaseObject child : children) {
				if (child.getParentId() != null && child.getParentId().equals(folderId) || folderId == null) {
					ObjectInFolderDataImpl oifd = new ObjectInFolderDataImpl();
					if (includePathSegments != null && includePathSegments) {
						oifd.setPathSegment(child.getName());
					}

					Set<String> filterCollection = Helpers.splitFilter(filter);
					ObjectData objectData = CmisObjectService.Impl.compileObjectData(repositoryId, child,
							filterCollection, includeAllowableActions, false, true, objectInfos, renditionFilter,
							includeRelationships, userObject);
					oifd.setObject(objectData);
					folderList.add(oifd);
					if (objectInfos != null) {
						ObjectInfoImpl objectInfo = new ObjectInfoImpl();
						objectInfos.addObjectInfo(objectInfo);
					}
					result.setObjects(folderList);
					result.setNumItems(BigInteger.valueOf(children.size()));
					result.setHasMoreItems(children.size() > 0 + folderList.size());

					LOG.debug("getChildrenIntern result for this folderId: {}, child count: {}", folderId,
							result != null ? result.getNumItems() : null);

				}
			}
			return result;
		}

		public static List<ObjectInFolderContainer> getDescendantsRelationObjects(String repositoryId, String folderId,
				String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
				String renditionFilter, Boolean includePathSegment, int level, int levels, boolean b,
				ObjectInfoHandler objectInfos, IUserObject userObject, String typeId) {
			LOG.debug("getDescendantsRelationObjects for folder data: {}", folderId);
			List<ObjectInFolderContainer> childrenOfFolderId = new ArrayList<ObjectInFolderContainer>();
			List<? extends IBaseObject> source = DBUtils.RelationshipDAO.getRelationshipBySourceId(repositoryId,
					folderId.toString(), 0, 0, null, typeId);
			List<ObjectInFolderData> folderList = new ArrayList<ObjectInFolderData>();
			ObjectInFolderListImpl result = new ObjectInFolderListImpl();
			source.forEach(relId -> {
				ObjectInFolderDataImpl oifd = new ObjectInFolderDataImpl();
				IBaseObject targetObject = DBUtils.BaseDAO.getByObjectId(repositoryId,
						relId.getProperties().get("cmis:targetId").toString(), null, relId.getTypeId());
				Set<String> filterCollection = Helpers.splitFilter(filter);
				String name = targetObject.getName();
				name = name + "," + relId.getProperties().get("relation_name").toString();
				targetObject.setName(name);
				ObjectData objectData = CmisObjectService.Impl.compileObjectData(repositoryId, targetObject,
						filterCollection, includeAllowableActions, true, true, objectInfos, renditionFilter,
						includeRelationships, userObject);
				oifd.setObject(objectData);

				folderList.add(oifd);
				if (objectInfos != null) {
					ObjectInfoImpl objectInfo = new ObjectInfoImpl();
					objectInfos.addObjectInfo(objectInfo);
				}
				result.setObjects(folderList);
				result.setNumItems(BigInteger.valueOf(source.size()));
				result.setHasMoreItems(source.size() > 0 + folderList.size());
			});
			if (0 != result.getObjects().size()) {
				for (ObjectInFolderData child : result.getObjects()) {
					ObjectInFolderContainerImpl oifc = new ObjectInFolderContainerImpl();
					String childId = child.getObject().getId();
					List<ObjectInFolderContainer> subChildren = getDescendantsRelationObjects(repositoryId, childId,
							filter, includeAllowableActions, includeRelationships, renditionFilter, includePathSegment,
							level, level, b, objectInfos, userObject, typeId);
					oifc.setObject(child);
					if (0 != subChildren.size()) {
						oifc.setChildren(subChildren);
					}
					childrenOfFolderId.add(oifc);
				}
			}
			return childrenOfFolderId;
		}

		/**
		 * Gets the parent folder object for the specified folder
		 */
		public static ObjectData getFolderParent(String repositoryId, String folderId, String filter,
				ObjectInfoHandler objectInfos, IUserObject userObject, String typeId)
				throws CmisInvalidArgumentException {
			ObjectData res = getFolderParentIntern(repositoryId, folderId, filter, false, IncludeRelationships.NONE,
					userObject, objectInfos, typeId);
			if (res == null) {
				LOG.error("getFolderParent cannot get parent of a root folder of: {}, repository: {}", folderId,
						repositoryId);
				throw new CmisInvalidArgumentException("Cannot get parent of a root folder");
			}
			if (res != null) {
				LOG.debug("Parent for folderId: {}, and result object count: {}", folderId, res);
			}
			return res;
		}

		/**
		 * Return the parent in the format of ObjectData.
		 */
		private static ObjectData getFolderParentIntern(String repositoryId, String folderId, String filter,
				Boolean includeAllowableActions, IncludeRelationships includeRelationships, IUserObject user,
				ObjectInfoHandler objectInfos, String typeId) {
			IBaseObject folderParent = null;
			IBaseObject data = DBUtils.BaseDAO.getByObjectId(repositoryId, folderId, null, typeId);
			folderParent = DBUtils.BaseDAO.getByObjectId(repositoryId, data.getParentId(), null, data.getTypeId());
			Set<String> filterCollection = Helpers.splitFilter(filter);
			ObjectData objectData = CmisObjectService.Impl.compileObjectData(repositoryId, folderParent,
					filterCollection, includeAllowableActions, false, true, objectInfos, null, includeRelationships,
					user);
			return objectData;
		}

		/**
		 * Gets the set of descendant folder objects contained in the specified folder
		 */
		public static List<ObjectInFolderContainer> getFolderTree(String repositoryId, String folderId,
				BigInteger depth, String filter, Boolean includeAllowableActions,
				IncludeRelationships includeRelationships, String renditionFilter, Boolean includePathSegment,
				ObjectInfoHandler objectInfos, IUserObject userObject, String typeId)
				throws CmisInvalidArgumentException {
			int levels;
			if (depth == null) {
				levels = 2; // one of the recommended defaults (should it be
			} else if (depth.intValue() == 0) {
				LOG.error("getFolderTree a zero depth is not allowed for getDescendants() in repository: {}",
						repositoryId);
				throw new CmisInvalidArgumentException("A zero depth is not allowed for getDescendants().");
			} else {
				levels = depth.intValue();
			}
			int level = 0;
			List<ObjectInFolderContainer> result = getFolderTreeIntern(repositoryId, folderId, filter,
					includeAllowableActions, includeRelationships, renditionFilter, includePathSegment, level, levels,
					true, objectInfos, userObject, typeId);
			if (result != null) {
				LOG.debug("descendant folder objects count: {}", result.size());
			}
			return result;
		}

		/**
		 * Return the folder tree as a list in the format of ObjectInFolderContainer.
		 */
		private static List<ObjectInFolderContainer> getFolderTreeIntern(String repositoryId, String folderId,
				String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
				String renditionFilter, Boolean includePathSegments, int level, int maxLevels, boolean folderOnly,
				ObjectInfoHandler objectInfos, IUserObject userObject, String typeId) {
			List<ObjectInFolderContainer> folderTree = null;
			MNavigationServiceDAO navigationMorphiaDAO = DatabaseServiceFactory.getInstance(repositoryId)
					.getObjectService(repositoryId, MNavigationServiceDAO.class);
			IBaseObject data = DBUtils.BaseDAO.getByObjectId(repositoryId, folderId, null, typeId);
			String path = "," + folderId + ",";

			List<AccessControlListImplExt> mAcl = getParentAcl(repositoryId, data.getInternalPath(), data.getAcl(),
					typeId);
			List<? extends IBaseObject> children = new ArrayList<>();
			String[] principalIds = com.pogeyan.cmis.api.utils.Helpers.getPrincipalIds(userObject);
			boolean objectOnly = true;
			if (mAcl != null && mAcl.size() > 0) {
				for (AccessControlListImplExt acl : mAcl) {
					if (acl != null) {
						if (acl.getAclPropagation().equalsIgnoreCase("PROPAGATE")) {
							List<Ace> listAce = acl.getAces().stream()
									.filter(t -> Arrays.stream(principalIds).parallel()
											.anyMatch(x -> Objects.equals(x.toLowerCase(),
													t.getPrincipalId().toLowerCase())) == true)
									.collect(Collectors.toList());
							if (listAce.size() >= 1) {
								children = navigationMorphiaDAO.getFolderTreeIds(path, principalIds, false);
								objectOnly = false;
								break;
							}
						}

						if (acl.getAclPropagation().equalsIgnoreCase("REPOSITORYDETERMINED")) {
							objectOnly = true;
						}
					}
				}
			}
			// Acl Propagation ObjectOnly
			if (objectOnly) {
				children = navigationMorphiaDAO.getFolderTreeIds(path, principalIds, true);
			}
			List<String> listOfParentIds = new ArrayList<>();
			folderTree = getDescendants(repositoryId, children, folderId, includePathSegments, filter,
					includeAllowableActions, objectInfos, renditionFilter, includeRelationships, userObject,
					listOfParentIds);
			folderTree = getDifferenceDescendants(repositoryId, children, folderId, includePathSegments, filter,
					includeAllowableActions, objectInfos, renditionFilter, includeRelationships, userObject,
					listOfParentIds, folderTree);
			return folderTree;
		}

		/**
		 * Gets the parent folder(s) for the specified nonfolder object
		 */
		public static List<ObjectParentData> getObjectParents(String repositoryId, String objectId, String filter,
				Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
				Boolean includeRelativePathSegment, ObjectInfoHandler objectInfos, IUserObject userObject,
				String typeId) {
			List<ObjectParentData> result = getObjectParentsIntern(repositoryId, objectId, filter, objectInfos,
					includeAllowableActions, includeRelationships, renditionFilter, includeRelativePathSegment,
					userObject, typeId);
			if (result != null) {
				LOG.debug("getObjectParents result object count: {}", result.size());
			}
			return result;
		}

		/**
		 * Return the object parent list in the format of ObjectParentData.
		 */
		private static List<ObjectParentData> getObjectParentsIntern(String repositoryId, String objectId,
				String filter, ObjectInfoHandler objectInfos, Boolean includeAllowableActions,
				IncludeRelationships includeRelationships, String renditionFilter, Boolean includeRelativePathSegment,
				IUserObject userObject, String typeId) {
			Set<String> filterCollection = Helpers.splitFilter(filter);

			List<ObjectParentData> objectParent = new ArrayList<ObjectParentData>();
			IBaseObject resultData = null;
			DatabaseServiceFactory.getInstance(repositoryId).getObjectService(repositoryId, MBaseObjectDAO.class);
			IBaseObject data = DBUtils.BaseDAO.getByObjectId(repositoryId, objectId, null, typeId);
			String[] queryResult = data.getInternalPath().split(",");
			int i = queryResult.length - 1;
			for (String result : queryResult) {
				if (!result.isEmpty()) {
					resultData = DBUtils.BaseDAO.getByObjectId(repositoryId, result, null, data.getTypeId());
					if (resultData.getBaseId() == BaseTypeId.CMIS_FOLDER) {
						ObjectData objectData = CmisObjectService.Impl.compileObjectData(repositoryId, resultData,
								filterCollection, includeAllowableActions, false, true, objectInfos, renditionFilter,
								includeRelationships, userObject);
						ObjectParentDataImpl parent = new ObjectParentDataImpl();
						parent.setObject(objectData);
						parent.setRelativePathSegment(i == 1 ? data.getName()
								: DBUtils.BaseDAO.getByObjectId(repositoryId, queryResult[i], null, data.getTypeId())
										.getName() + "/" + data.getName());
						i--;
						objectParent.add(parent);
					}
				}
			}

			return objectParent;
		}

		public static ObjectList getCheckedOutDocs(String repositoryId, String folderId, String filter, String orderBy,
				Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
				BigInteger maxItems, BigInteger skipCount, ExtensionsData extension, ObjectInfoHandler objectInfos,
				IUserObject userObject, String typeId) {
			int maxItemsInt = maxItems == null ? -1 : maxItems.intValue();
			int skipCountInt = skipCount == null ? 0 : skipCount.intValue();
			ObjectList res = getCheckedOutIntern(repositoryId, folderId, filter, orderBy, includeAllowableActions,
					includeRelationships, renditionFilter, maxItemsInt, skipCountInt, extension, objectInfos,
					userObject, typeId);
			if (res != null) {
				LOG.debug("checkedout objects: {}", res.getObjects());
			}
			return res;
		}

		private static ObjectList getCheckedOutIntern(String repositoryId, String folderId, String filter,
				String orderBy, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
				String renditionFilter, int maxItems, int skipCount, ExtensionsData extension,
				ObjectInfoHandler objectInfos, IUserObject userObject, String typeId)
				throws CmisObjectNotFoundException {
			MDocumentObjectDAO documentMorphiaDAO = DatabaseServiceFactory.getInstance(repositoryId)
					.getObjectService(repositoryId, MDocumentObjectDAO.class);
			List<? extends IDocumentObject> document = new ArrayList<>();
			ObjectListImpl results = new ObjectListImpl();
			List<ObjectData> odList = new ArrayList<ObjectData>();
			String[] principalIds = com.pogeyan.cmis.api.utils.Helpers.getPrincipalIds(userObject);
			long documentCount = 0;
			if (orderBy != null) {
				orderBy = com.pogeyan.cmis.api.utils.Helpers.getQueryName(orderBy);
			}
			if (folderId == null) {
				document = documentMorphiaDAO.getCheckOutDocs(folderId, principalIds, true, maxItems, skipCount,
						orderBy);
				documentCount = documentMorphiaDAO.getCheckOutDocsSize(folderId, principalIds, true);
			} else {
				IBaseObject data = DBUtils.BaseDAO.getByObjectId(repositoryId, folderId, null, typeId);
				if (data == null) {
					LOG.error("getCheckedOutIntern unknown object id: {}, repository: {}", folderId, repositoryId);
					throw new CmisObjectNotFoundException("Unknown object id: " + folderId);
				}
				List<AccessControlListImplExt> mAcl = getParentAcl(repositoryId, data.getInternalPath(), data.getAcl(),
						typeId);
				boolean objectOnly = true;
				if (mAcl != null && mAcl.size() > 0) {
					for (AccessControlListImplExt acl : mAcl) {
						if (acl != null) {
							if (acl.getAclPropagation().equalsIgnoreCase("PROPAGATE")) {
								List<Ace> listAce = acl.getAces().stream()
										.filter(t -> Arrays.stream(principalIds).parallel()
												.anyMatch(x -> Objects.equals(x.toLowerCase(),
														t.getPrincipalId().toLowerCase())) == true)
										.collect(Collectors.toList());
								if (listAce.size() >= 1) {
									document = documentMorphiaDAO.getCheckOutDocs(folderId, principalIds, false,
											maxItems, skipCount, orderBy);
									documentCount = documentMorphiaDAO.getCheckOutDocsSize(folderId, principalIds,
											false);
									objectOnly = false;
									break;
								}
							}

							if (acl.getAclPropagation().equalsIgnoreCase("REPOSITORYDETERMINED")) {
								objectOnly = true;
							}
						}
					}
				}
				// Acl Propagation ObjectOnly
				if (objectOnly) {
					document = documentMorphiaDAO.getCheckOutDocs(folderId, principalIds, true, maxItems, skipCount,
							orderBy);
					documentCount = documentMorphiaDAO.getCheckOutDocsSize(folderId, principalIds, true);
				}
			}

			for (

			IDocumentObject checkedOutDocs : document) {
				Set<String> filterCollection = Helpers.splitFilter(filter);
				ObjectData objectData = CmisObjectService.Impl.compileObjectData(repositoryId, checkedOutDocs,
						filterCollection, includeAllowableActions, false, true, objectInfos, renditionFilter,
						includeRelationships, userObject);
				odList.add(objectData);
				if (objectInfos != null) {
					ObjectInfoImpl objectInfo = new ObjectInfoImpl();
					objectInfos.addObjectInfo(objectInfo);
				}
			}

			results.setObjects(odList);
			results.setNumItems(BigInteger.valueOf(documentCount));
			results.setHasMoreItems(skipCount + 1 * maxItems < documentCount);
			return results;
		}

		public static List<AccessControlListImplExt> getParentAcl(String repositoryId, String dataPath,
				AccessControlListImplExt dataAcl, String typeId) {
			LOG.debug("getParentAcl for data path : {}", dataPath);
			List<AccessControlListImplExt> acl = null;
			String[] queryResult = dataPath.split(",");
			if (queryResult.length > 0) {
				List<IBaseObject> folderChildren = Stream.of(queryResult).filter(t -> !t.isEmpty())
						.map(t -> DBUtils.BaseDAO.getByObjectId(repositoryId, t, null, typeId))
						.collect(Collectors.<IBaseObject>toList());
				if (folderChildren.size() == 1) {
					acl = new ArrayList<>();
					acl.add(dataAcl);
				} else {
					acl = folderChildren.stream().filter(t -> t.getAcl() != null).map(t -> t.getAcl())
							.collect(Collectors.<AccessControlListImplExt>toList());
				}
			} else {
				acl = new ArrayList<>();
				acl.add(dataAcl);
			}
			return acl;
		}

		private static String getOrderByName(String orderBy) {
			String order[] = orderBy.split("\\s+");
			if (order.length > 1) {
				return com.pogeyan.cmis.api.utils.Helpers.getQueryName(orderBy.split("\\s+")[0]) + " "
						+ orderBy.split("\\s+")[1];
			} else {
				return com.pogeyan.cmis.api.utils.Helpers.getQueryName(orderBy);
			}
		}
	}

}
