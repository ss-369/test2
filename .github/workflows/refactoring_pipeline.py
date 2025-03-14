#!/usr/bin/env python3
import os
import google.generativeai as genai
from github import Github
from datetime import datetime
import logging
from typing import List, Dict

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# Configuration from environment variables
GEMINI_API_KEY = os.environ["GEMINI_API_KEY"]
GITHUB_TOKEN = os.environ["GITHUB_TOKEN"]
REPO_NAME = os.environ["GITHUB_REPOSITORY"]  # Set in GitHub Actions or local environment
TARGET_FOLDER = os.environ.get("TARGET_FOLDER", "reader-core/src/main/java/com/sismics/reader/core/dao/lucene")
BASE_BRANCH = os.environ.get("BASE_BRANCH", "master")

# Configure Gemini
genai.configure(api_key=GEMINI_API_KEY)

def get_file_content(repo, path: str) -> str:
    """Get content of a file from GitHub."""
    try:
        file_content = repo.get_contents(path, ref=BASE_BRANCH)
        if isinstance(file_content, list):
            return None
        return file_content.decoded_content.decode('utf-8')
    except Exception as e:
        logging.error(f"Error reading file {path}: {str(e)}")
        return None

def get_java_files_in_folder(repo, folder_path: str) -> List[Dict]:
    """Get all Java files in specified folder."""
    files = []
    try:
        contents = repo.get_contents(folder_path, ref=BASE_BRANCH)
        logging.info(f"Scanning folder: {folder_path}")
        for content in contents:
            if content.type == "file" and content.path.endswith('.java'):
                logging.info(f"Found Java file: {content.path}")
                code = get_file_content(repo, content.path)
                if code:
                    files.append({
                        'path': content.path,
                        'content': code,
                        'sha': content.sha
                    })
    except Exception as e:
        logging.error(f"Error accessing folder {folder_path}: {str(e)}")
    return files

def detect_design_smells(files: List[Dict]) -> List[Dict]:
    """Detect design smells using Gemini."""
    model = genai.GenerativeModel('gemini-pro')
    smells = []
    # Compare each unique pair of files
    for i in range(len(files)):
        for j in range(i + 1, len(files)):
            file1 = files[i]
            file2 = files[j]
            logging.info(f"Analyzing pair:\n - File 1: {file1['path']}\n - File 2: {file2['path']}")
            prompt = f"""Analyze these two Java files for design smells:
File 1 ({file1['path']}):
{file1['content']}
File 2 ({file2['path']}):
{file2['content']}
Focus on design smells like:
1. feature_envy
2. inappropriate_intimacy
3. shotgun_surgery
4. parallel_inheritance
5. divergent_change
6. duplicate_abstraction
7. insufficient_modularisation
8. cyclically_dependent_modularisation
9. multifaceted_abstraction
10. broken_modularisation
Return ONLY in this exact format if a smell is found:
<smell_type>: <description>
Use underscore_case for the smell type.
Or return exactly "No design smells detected" if no smells are found.
"""
            try:
                response = model.generate_content(prompt)
                analysis = response.text.strip()
                if "No design smells detected" not in analysis:
                    try:
                        smell_type, description = analysis.split(": ", 1)
                        smell_type = smell_type.strip().lower().replace(' ', '_')
                        smell_info = {
                            'type': smell_type,
                            'files': [file1['path'], file2['path']],
                            'description': description.strip(),
                            'file_contents': {
                                file1['path']: file1,
                                file2['path']: file2
                            }
                        }
                        smells.append(smell_info)
                        logging.info(f"Detected smell: {smell_type}")
                    except ValueError:
                        logging.error(f"Unexpected response format for files {file1['path']} and {file2['path']}")
            except Exception as e:
                logging.error(f"Error analyzing files: {str(e)}")
    return smells

def refactor_code(smell: Dict) -> Dict[str, Dict]:
    """Refactor code based on identified design smell."""
    model = genai.GenerativeModel('gemini-pro')
    refactored_files = {}
    for file_path, file_info in smell['file_contents'].items():
        logging.info(f"Refactoring file: {file_path}")
        prompt = f"""Refactor this Java code to fix the design smell: {smell['type']}
Description of the issue: {smell['description']}
Original code:
{file_info['content']}
Requirements:
1. Preserve all functionality
2. Follow SOLID principles
3. Improve code organization
4. Reduce coupling
5. Enhance maintainability
6. Keep the same package and import statements
7. Return ONLY the refactored code, no explanations
Return the complete refactored code:"""
        try:
            response = model.generate_content(prompt)
            refactored_code = response.text.strip()
            if refactored_code:
                refactored_files[file_path] = {
                    'content': refactored_code,
                    'sha': file_info['sha']
                }
                logging.info(f"Successfully refactored {file_path}")
            else:
                logging.warning(f"Empty refactored code received for {file_path}")
        except Exception as e:
            logging.error(f"Error refactoring file {file_path}: {str(e)}")
    return refactored_files

def create_pull_request(repo, refactored_files: Dict[str, Dict], smell: Dict) -> str:
    """Create pull request with refactored code."""
    try:
        sanitized_smell = smell['type'].strip().lower().replace(' ', '-').replace('_', '-')
        timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
        new_branch = f"refactor/{sanitized_smell}/{timestamp}"
        base_sha = repo.get_branch(BASE_BRANCH).commit.sha
        repo.create_git_ref(ref=f"refs/heads/{new_branch}", sha=base_sha)
        
        for file_path, file_info in refactored_files.items():
            repo.update_file(
                file_path,
                f"Refactor: Fix {smell['type']} in {file_path}",
                file_info['content'],
                file_info['sha'],
                branch=new_branch
            )
            
        pr_title = f"Refactor: Fix {smell['type']}"
        pr_body = f"""Automated refactoring to address design smell
Type: {smell['type']}
Files affected: {', '.join(smell['files'])}
Description:
{smell['description']}

This pull request was automatically generated by the refactoring pipeline.
Please review the changes carefully before merging.
"""
        pr = repo.create_pull(
            title=pr_title,
            body=pr_body,
            base=BASE_BRANCH,
            head=new_branch
        )
        logging.info(f"Pull Request created: {pr.html_url}")
        return pr.html_url
    except Exception as e:
        logging.error(f"Error creating pull request: {str(e)}")
        return None

def main():
    try:
        g = Github(GITHUB_TOKEN)
        repo = g.get_repo(REPO_NAME)
        files = get_java_files_in_folder(repo, TARGET_FOLDER)
        
        if not files:
            logging.warning("No Java files found in the specified folder")
            return
            
        smells = detect_design_smells(files)
        logging.info(f"Found {len(smells)} design smells")
        
        for smell in smells:
            refactored_files = refactor_code(smell)
            if refactored_files:
                pr_url = create_pull_request(repo, refactored_files, smell)
                if pr_url:
                    logging.info(f"Successfully created pull request: {pr_url}")
                else:
                    logging.error("Failed to create pull request")
    except Exception as e:
        logging.error(f"Error in main execution: {str(e)}")
        raise e

if __name__ == "__main__":
    main()